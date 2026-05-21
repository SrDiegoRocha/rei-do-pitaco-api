package com.example.futbet.service;

import com.example.futbet.dto.response.StandingsResponse;
import com.example.futbet.entity.Match;
import com.example.futbet.entity.PhaseGroup;
import com.example.futbet.entity.PhaseTeam;
import com.example.futbet.entity.Team;
import com.example.futbet.entity.Tournament;
import com.example.futbet.entity.TournamentPhase;
import com.example.futbet.entity.TournamentSettings;
import com.example.futbet.enums.MatchStatus;
import com.example.futbet.enums.TiebreakCriteria;
import com.example.futbet.enums.TournamentPhaseType;
import com.example.futbet.exception.PhaseNotFoundException;
import com.example.futbet.exception.TournamentNotFoundException;
import com.example.futbet.repository.MatchRepository;
import com.example.futbet.repository.PhaseGroupRepository;
import com.example.futbet.repository.PhaseTeamRepository;
import com.example.futbet.repository.TournamentPhaseRepository;
import com.example.futbet.repository.TournamentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StandingsService {

    private final TournamentRepository tournamentRepository;
    private final TournamentPhaseRepository phaseRepository;
    private final PhaseGroupRepository groupRepository;
    private final PhaseTeamRepository phaseTeamRepository;
    private final MatchRepository matchRepository;

    public StandingsService(
            TournamentRepository tournamentRepository,
            TournamentPhaseRepository phaseRepository,
            PhaseGroupRepository groupRepository,
            PhaseTeamRepository phaseTeamRepository,
            MatchRepository matchRepository
    ) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.groupRepository = groupRepository;
        this.phaseTeamRepository = phaseTeamRepository;
        this.matchRepository = matchRepository;
    }

    @Transactional(readOnly = true)
    public StandingsResponse compute(UUID tournamentPublicId, UUID phasePublicId) {
        Tournament tournament = tournamentRepository.findByPublicIdAndActiveTrue(tournamentPublicId)
                .orElseThrow(TournamentNotFoundException::new);
        TournamentPhase phase = phaseRepository
                .findByPublicIdAndTournamentPublicId(phasePublicId, tournamentPublicId)
                .orElseThrow(PhaseNotFoundException::new);

        List<StandingsResponse.GroupStandings> groups = new ArrayList<>();

        if (phase.getPhaseType() == TournamentPhaseType.GROUPS) {
            List<PhaseGroup> phaseGroups = groupRepository
                    .findAllByPhasePublicIdOrderByPositionAsc(phasePublicId);
            for (PhaseGroup group : phaseGroups) {
                groups.add(computeGroupStandings(tournament, phase, group));
            }
        } else {
            groups.add(computeGroupStandings(tournament, phase, null));
        }

        return new StandingsResponse(phasePublicId, groups);
    }

    StandingsResponse.GroupStandings computeGroupStandings(
            Tournament tournament,
            TournamentPhase phase,
            PhaseGroup group
    ) {
        List<PhaseTeam> phaseTeams = phaseTeamRepository.findAllByPhasePublicId(phase.getPublicId());
        List<PhaseTeam> teams = phaseTeams.stream()
                .filter(pt -> {
                    if (group == null) return true;
                    return pt.getGroup() != null && pt.getGroup().getId().equals(group.getId());
                })
                .toList();

        Map<Long, StandingAccumulator> table = new HashMap<>();
        for (PhaseTeam pt : teams) {
            table.put(pt.getTeam().getId(), new StandingAccumulator(pt.getTeam()));
        }

        List<Match> allMatches = matchRepository.findAllByPhasePublicId(phase.getPublicId());
        for (Match match : allMatches) {
            if (match.getStatus() != MatchStatus.COMPLETED) {
                continue;
            }
            if (group != null) {
                if (match.getGroup() == null || !match.getGroup().getId().equals(group.getId())) {
                    continue;
                }
            }
            StandingAccumulator home = table.get(match.getHomeTeam().getId());
            StandingAccumulator away = table.get(match.getAwayTeam().getId());
            if (home == null || away == null) {
                continue;
            }
            int homeScore = match.getHomeScore() == null ? 0 : match.getHomeScore();
            int awayScore = match.getAwayScore() == null ? 0 : match.getAwayScore();
            home.played++;
            away.played++;
            home.goalsFor += homeScore;
            home.goalsAgainst += awayScore;
            away.goalsFor += awayScore;
            away.goalsAgainst += homeScore;
            if (homeScore > awayScore) {
                home.wins++;
                away.losses++;
            } else if (homeScore < awayScore) {
                away.wins++;
                home.losses++;
            } else {
                home.draws++;
                away.draws++;
            }
        }

        TournamentSettings settings = tournament.getSettings();
        for (StandingAccumulator acc : table.values()) {
            acc.points = acc.wins * settings.getWinPoints()
                    + acc.draws * settings.getDrawPoints()
                    + acc.losses * settings.getLossPoints();
        }

        List<TiebreakCriteria> tiebreaks = tournament.getTiebreakCriteria().stream()
                .sorted(Comparator.comparingInt(c -> c.getPosition()))
                .map(c -> c.getCriteria())
                .toList();

        List<StandingAccumulator> ordered = new ArrayList<>(table.values());
        ordered.sort(buildComparator(tiebreaks, allMatches, group));

        List<StandingsResponse.StandingRow> rows = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            StandingAccumulator a = ordered.get(i);
            rows.add(new StandingsResponse.StandingRow(
                    i + 1,
                    a.team.getPublicId(),
                    a.team.getName(),
                    a.team.getShortName(),
                    a.team.getBadgeUrl(),
                    a.played, a.wins, a.draws, a.losses,
                    a.goalsFor, a.goalsAgainst,
                    a.goalsFor - a.goalsAgainst,
                    a.points
            ));
        }
        return new StandingsResponse.GroupStandings(
                group != null ? group.getPublicId() : null,
                group != null ? group.getName() : null,
                rows
        );
    }

    Comparator<StandingAccumulator> buildComparator(
            List<TiebreakCriteria> tiebreaks,
            List<Match> matches,
            PhaseGroup groupContext
    ) {
        Comparator<StandingAccumulator> chain = (a, b) -> 0;
        for (TiebreakCriteria criterion : tiebreaks) {
            Comparator<StandingAccumulator> step = switch (criterion) {
                case POINTS -> Comparator.comparingInt((StandingAccumulator s) -> s.points).reversed();
                case WINS -> Comparator.comparingInt((StandingAccumulator s) -> s.wins).reversed();
                case GOAL_DIFFERENCE -> Comparator.comparingInt(
                        (StandingAccumulator s) -> s.goalsFor - s.goalsAgainst
                ).reversed();
                case GOALS_FOR -> Comparator.comparingInt((StandingAccumulator s) -> s.goalsFor).reversed();
                case FEWEST_LOSSES -> Comparator.comparingInt((StandingAccumulator s) -> s.losses);
                case HEAD_TO_HEAD -> headToHead(matches, groupContext);
            };
            chain = chain.thenComparing(step);
        }
        chain = chain.thenComparing((a, b) -> a.team.getName().compareToIgnoreCase(b.team.getName()));
        return chain;
    }

    private Comparator<StandingAccumulator> headToHead(List<Match> matches, PhaseGroup groupContext) {
        return (a, b) -> {
            int pointsA = 0;
            int pointsB = 0;
            for (Match m : matches) {
                if (m.getStatus() != MatchStatus.COMPLETED) continue;
                if (groupContext != null) {
                    if (m.getGroup() == null || !m.getGroup().getId().equals(groupContext.getId())) continue;
                }
                Long homeId = m.getHomeTeam().getId();
                Long awayId = m.getAwayTeam().getId();
                Long ida = a.team.getId();
                Long idb = b.team.getId();
                if (!((homeId.equals(ida) && awayId.equals(idb)) || (homeId.equals(idb) && awayId.equals(ida)))) {
                    continue;
                }
                int homeScore = m.getHomeScore() == null ? 0 : m.getHomeScore();
                int awayScore = m.getAwayScore() == null ? 0 : m.getAwayScore();
                if (homeScore > awayScore) {
                    if (homeId.equals(ida)) pointsA += 3;
                    else pointsB += 3;
                } else if (awayScore > homeScore) {
                    if (awayId.equals(ida)) pointsA += 3;
                    else pointsB += 3;
                } else {
                    pointsA += 1;
                    pointsB += 1;
                }
            }
            return Integer.compare(pointsB, pointsA);
        };
    }

    static class StandingAccumulator {
        final Team team;
        int played, wins, draws, losses;
        int goalsFor, goalsAgainst;
        int points;

        StandingAccumulator(Team team) {
            this.team = team;
        }
    }
}
