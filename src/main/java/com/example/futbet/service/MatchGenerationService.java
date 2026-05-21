package com.example.futbet.service;

import com.example.futbet.dto.response.MatchResponse;
import com.example.futbet.entity.Match;
import com.example.futbet.entity.PhaseGroup;
import com.example.futbet.entity.PhaseTeam;
import com.example.futbet.entity.Team;
import com.example.futbet.entity.Tournament;
import com.example.futbet.entity.TournamentPhase;
import com.example.futbet.enums.MatchGenerationMode;
import com.example.futbet.enums.MatchLegMode;
import com.example.futbet.enums.MatchStatus;
import com.example.futbet.enums.TournamentPhaseType;
import com.example.futbet.enums.TournamentStatus;
import com.example.futbet.exception.MatchGenerationException;
import com.example.futbet.exception.NotTournamentOwnerException;
import com.example.futbet.exception.PhaseNotFoundException;
import com.example.futbet.exception.TournamentNotEditableException;
import com.example.futbet.exception.TournamentNotFoundException;
import com.example.futbet.mapper.MatchMapper;
import com.example.futbet.repository.MatchRepository;
import com.example.futbet.repository.PhaseGroupRepository;
import com.example.futbet.repository.PhaseTeamRepository;
import com.example.futbet.repository.TournamentPhaseRepository;
import com.example.futbet.repository.TournamentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MatchGenerationService {

    private final TournamentRepository tournamentRepository;
    private final TournamentPhaseRepository phaseRepository;
    private final PhaseGroupRepository groupRepository;
    private final PhaseTeamRepository phaseTeamRepository;
    private final MatchRepository matchRepository;
    private final MatchMapper matchMapper;
    private final SecureRandom random = new SecureRandom();

    public MatchGenerationService(
            TournamentRepository tournamentRepository,
            TournamentPhaseRepository phaseRepository,
            PhaseGroupRepository groupRepository,
            PhaseTeamRepository phaseTeamRepository,
            MatchRepository matchRepository,
            MatchMapper matchMapper
    ) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.groupRepository = groupRepository;
        this.phaseTeamRepository = phaseTeamRepository;
        this.matchRepository = matchRepository;
        this.matchMapper = matchMapper;
    }

    @Transactional
    public List<MatchResponse> generate(
            UUID ownerPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId
    ) {
        Tournament tournament = tournamentRepository.findByPublicIdAndActiveTrue(tournamentPublicId)
                .orElseThrow(TournamentNotFoundException::new);
        if (!tournament.getOwner().getPublicId().equals(ownerPublicId)) {
            throw new NotTournamentOwnerException();
        }
        if (tournament.getStatus() == TournamentStatus.FINISHED) {
            throw new TournamentNotEditableException(
                    tournament.getStatus(),
                    "cannot generate matches"
            );
        }
        TournamentPhase phase = phaseRepository
                .findByPublicIdAndTournamentPublicId(phasePublicId, tournamentPublicId)
                .orElseThrow(PhaseNotFoundException::new);
        if (phase.getMatchGenerationMode() != MatchGenerationMode.AUTOMATIC) {
            throw new MatchGenerationException(
                    "Phase has matchGenerationMode=MANUAL; cannot auto-generate"
            );
        }

        List<Match> created = switch (phase.getPhaseType()) {
            case ROUND_ROBIN -> generateRoundRobinPhase(phase);
            case GROUPS -> generateGroupsPhase(phase);
            case KNOCKOUT -> generateKnockoutPhase(phase);
        };

        return created.stream().map(matchMapper::toResponse).toList();
    }

    private List<Match> generateRoundRobinPhase(TournamentPhase phase) {
        if (matchRepository.countByPhaseId(phase.getId()) > 0) {
            throw new MatchGenerationException("Phase already has matches; clear them before generating");
        }
        List<Team> teams = phaseTeamRepository.findAllByPhasePublicId(phase.getPublicId())
                .stream().map(PhaseTeam::getTeam).toList();
        if (teams.size() < 2) {
            throw new MatchGenerationException("Phase needs at least 2 teams to generate matches");
        }
        return generateRoundRobinForBlock(phase, null, new ArrayList<>(teams), 1);
    }

    private List<Match> generateGroupsPhase(TournamentPhase phase) {
        if (matchRepository.countByPhaseId(phase.getId()) > 0) {
            throw new MatchGenerationException("Phase already has matches; clear them before generating");
        }
        List<PhaseGroup> groups = groupRepository
                .findAllByPhasePublicIdOrderByPositionAsc(phase.getPublicId());
        if (groups.isEmpty()) {
            throw new MatchGenerationException("GROUPS phase has no groups configured");
        }
        List<PhaseTeam> allTeams = phaseTeamRepository.findAllByPhasePublicId(phase.getPublicId());
        Map<Long, List<Team>> teamsByGroup = new HashMap<>();
        for (PhaseGroup g : groups) {
            teamsByGroup.put(g.getId(), new ArrayList<>());
        }
        for (PhaseTeam pt : allTeams) {
            if (pt.getGroup() == null) {
                throw new MatchGenerationException(
                        "Team '" + pt.getTeam().getName() + "' is not assigned to any group"
                );
            }
            teamsByGroup.get(pt.getGroup().getId()).add(pt.getTeam());
        }

        List<Match> all = new ArrayList<>();
        int roundOffset = 1;
        for (PhaseGroup group : groups) {
            List<Team> teams = teamsByGroup.get(group.getId());
            if (teams.size() < 2) {
                throw new MatchGenerationException(
                        "Group '" + group.getName() + "' needs at least 2 teams"
                );
            }
            all.addAll(generateRoundRobinForBlock(phase, group, new ArrayList<>(teams), roundOffset));
        }
        return all;
    }

    private List<Match> generateRoundRobinForBlock(
            TournamentPhase phase,
            PhaseGroup group,
            List<Team> teams,
            int firstRound
    ) {
        Collections.shuffle(teams, random);
        boolean odd = teams.size() % 2 != 0;
        if (odd) {
            teams.add(null);
        }
        int n = teams.size();
        int rounds = n - 1;
        boolean twoLegged = phase.getMatchLegMode() == MatchLegMode.TWO_LEGGED;

        List<Match> created = new ArrayList<>();
        List<Team> rotation = new ArrayList<>(teams);

        for (int r = 0; r < rounds; r++) {
            int round = firstRound + r;
            for (int i = 0; i < n / 2; i++) {
                Team home = rotation.get(i);
                Team away = rotation.get(n - 1 - i);
                if (home == null || away == null) continue;
                UUID tieId = UUID.randomUUID();
                created.add(persistMatch(phase, group, round, tieId, home, away));
                if (twoLegged) {
                    int returnRound = firstRound + rounds + r;
                    created.add(persistMatch(phase, group, returnRound, tieId, away, home));
                }
            }
            rotation = rotate(rotation);
        }
        return created;
    }

    private List<Team> rotate(List<Team> teams) {
        List<Team> rotated = new ArrayList<>(teams.size());
        rotated.add(teams.get(0));
        rotated.add(teams.get(teams.size() - 1));
        for (int i = 1; i < teams.size() - 1; i++) {
            rotated.add(teams.get(i));
        }
        return rotated;
    }

    private List<Match> generateKnockoutPhase(TournamentPhase phase) {
        List<Match> existing = matchRepository.findAllByPhasePublicId(phase.getPublicId());
        if (existing.isEmpty()) {
            return generateKnockoutFirstRound(phase);
        }
        return generateKnockoutNextRound(phase, existing);
    }

    private List<Match> generateKnockoutFirstRound(TournamentPhase phase) {
        List<Team> teams = phaseTeamRepository.findAllByPhasePublicId(phase.getPublicId())
                .stream().map(PhaseTeam::getTeam).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int n = teams.size();
        if (!isPowerOfTwo(n)) {
            throw new MatchGenerationException(
                    "KNOCKOUT requires a power of 2 of teams (got " + n + ")"
            );
        }
        if (n < 2) {
            throw new MatchGenerationException("KNOCKOUT needs at least 2 teams");
        }
        Collections.shuffle(teams, random);
        boolean twoLegged = phase.getMatchLegMode() == MatchLegMode.TWO_LEGGED;

        List<Match> created = new ArrayList<>();
        int homeRound = 1;
        int returnRound = 2;
        for (int i = 0; i < n / 2; i++) {
            Team home = teams.get(i);
            Team away = teams.get(n - 1 - i);
            UUID tieId = UUID.randomUUID();
            created.add(persistMatch(phase, null, homeRound, tieId, home, away));
            if (twoLegged) {
                created.add(persistMatch(phase, null, returnRound, tieId, away, home));
            }
        }
        return created;
    }

    private List<Match> generateKnockoutNextRound(TournamentPhase phase, List<Match> existing) {
        boolean twoLegged = phase.getMatchLegMode() == MatchLegMode.TWO_LEGGED;
        int maxRound = existing.stream().mapToInt(Match::getRound).max().orElse(0);

        List<Match> latestRoundMatches = existing.stream()
                .filter(m -> twoLegged ? (m.getRound() == maxRound - 1 || m.getRound() == maxRound)
                                       : m.getRound() == maxRound)
                .toList();
        for (Match m : latestRoundMatches) {
            if (m.getStatus() == MatchStatus.SCHEDULED) {
                throw new MatchGenerationException(
                        "Previous round still has unfinished matches"
                );
            }
        }

        Map<UUID, List<Match>> byTie = new HashMap<>();
        for (Match m : latestRoundMatches) {
            byTie.computeIfAbsent(m.getTieId(), k -> new ArrayList<>()).add(m);
        }

        List<Team> winners = new ArrayList<>();
        for (Map.Entry<UUID, List<Match>> entry : byTie.entrySet()) {
            Team winner = resolveTieWinner(entry.getValue());
            if (winner == null) {
                throw new MatchGenerationException(
                        "Tie " + entry.getKey() + " has no winner (draw on aggregate); resolve manually"
                );
            }
            winners.add(winner);
        }

        if (winners.size() == 1) {
            throw new MatchGenerationException("Phase already has a champion; no more rounds to generate");
        }

        Team finalLoserA = null;
        Team finalLoserB = null;
        boolean isFinalRound = winners.size() == 2 && phase.isHasThirdPlace();
        if (isFinalRound) {
            for (Map.Entry<UUID, List<Match>> entry : byTie.entrySet()) {
                Team loser = resolveTieLoser(entry.getValue());
                if (loser == null) continue;
                if (finalLoserA == null) finalLoserA = loser;
                else finalLoserB = loser;
            }
        }

        int nextHomeRound = twoLegged ? maxRound + 1 : maxRound + 1;
        int nextReturnRound = twoLegged ? maxRound + 2 : maxRound + 1;

        List<Match> created = new ArrayList<>();
        for (int i = 0; i < winners.size(); i += 2) {
            Team home = winners.get(i);
            Team away = winners.get(i + 1);
            UUID tieId = UUID.randomUUID();
            created.add(persistMatch(phase, null, nextHomeRound, tieId, home, away));
            if (twoLegged) {
                created.add(persistMatch(phase, null, nextReturnRound, tieId, away, home));
            }
        }
        if (isFinalRound && finalLoserA != null && finalLoserB != null) {
            UUID tieId = UUID.randomUUID();
            created.add(persistMatch(phase, null, nextHomeRound, tieId, finalLoserA, finalLoserB));
            if (twoLegged) {
                created.add(persistMatch(phase, null, nextReturnRound, tieId, finalLoserB, finalLoserA));
            }
        }
        return created;
    }

    private Team resolveTieWinner(List<Match> legs) {
        int homeAgg = 0;
        int awayAgg = 0;
        Team home = legs.get(0).getHomeTeam();
        Team away = legs.get(0).getAwayTeam();
        for (Match m : legs) {
            if (m.getStatus() != MatchStatus.COMPLETED) continue;
            int hs = m.getHomeScore() == null ? 0 : m.getHomeScore();
            int as = m.getAwayScore() == null ? 0 : m.getAwayScore();
            if (m.getHomeTeam().getId().equals(home.getId())) {
                homeAgg += hs;
                awayAgg += as;
            } else {
                homeAgg += as;
                awayAgg += hs;
            }
        }
        if (homeAgg > awayAgg) return home;
        if (awayAgg > homeAgg) return away;
        return null;
    }

    private Team resolveTieLoser(List<Match> legs) {
        Team winner = resolveTieWinner(legs);
        if (winner == null) return null;
        Team home = legs.get(0).getHomeTeam();
        Team away = legs.get(0).getAwayTeam();
        return winner.getId().equals(home.getId()) ? away : home;
    }

    private boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    private Match persistMatch(
            TournamentPhase phase,
            PhaseGroup group,
            int round,
            UUID tieId,
            Team home,
            Team away
    ) {
        Match match = Match.builder()
                .phase(phase)
                .group(group)
                .round(round)
                .tieId(tieId)
                .homeTeam(home)
                .awayTeam(away)
                .status(MatchStatus.SCHEDULED)
                .build();
        return matchRepository.save(match);
    }
}
