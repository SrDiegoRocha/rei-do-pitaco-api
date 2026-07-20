package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.response.MatchResponse;
import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.PhaseGroup;
import com.example.reidopitaco.entity.PhaseTeam;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.enums.MatchGenerationMode;
import com.example.reidopitaco.enums.MatchLegMode;
import com.example.reidopitaco.enums.MatchStatus;
import com.example.reidopitaco.enums.MatchType;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.enums.TournamentStatus;
import com.example.reidopitaco.exception.MatchGenerationException;
import com.example.reidopitaco.exception.NotTournamentOwnerException;
import com.example.reidopitaco.exception.PhaseNotFoundException;
import com.example.reidopitaco.exception.TournamentNotEditableException;
import com.example.reidopitaco.exception.TournamentNotFoundException;
import com.example.reidopitaco.mapper.MatchMapper;
import com.example.reidopitaco.repository.MatchRepository;
import com.example.reidopitaco.repository.PhaseGroupRepository;
import com.example.reidopitaco.repository.PhaseTeamRepository;
import com.example.reidopitaco.repository.TournamentPhaseRepository;
import com.example.reidopitaco.repository.TournamentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private final TieAggregateCalculator tieCalculator;
    private final SecureRandom random = new SecureRandom();

    public MatchGenerationService(
            TournamentRepository tournamentRepository,
            TournamentPhaseRepository phaseRepository,
            PhaseGroupRepository groupRepository,
            PhaseTeamRepository phaseTeamRepository,
            MatchRepository matchRepository,
            MatchMapper matchMapper,
            TieAggregateCalculator tieCalculator
    ) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.groupRepository = groupRepository;
        this.phaseTeamRepository = phaseTeamRepository;
        this.matchRepository = matchRepository;
        this.matchMapper = matchMapper;
        this.tieCalculator = tieCalculator;
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
                created.add(persistMatch(phase, group, round, tieId, home, away, MatchType.REGULAR));
                if (twoLegged) {
                    int returnRound = firstRound + rounds + r;
                    created.add(persistMatch(phase, group, returnRound, tieId, away, home, MatchType.REGULAR));
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
            created.add(persistMatch(phase, null, homeRound, tieId, home, away, MatchType.REGULAR));
            if (twoLegged) {
                created.add(persistMatch(phase, null, returnRound, tieId, away, home, MatchType.REGULAR));
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

        // Ordem canônica do chaveamento (a mesma do bracket read model): pernas ordenadas por
        // round/criação e confrontos pela criação da 1ª perna. Iterar o HashMap direto deixava a
        // ordem dos vencedores efetivamente aleatória — o emparelhamento da próxima rodada não
        // seguia a árvore exibida no bracket. Com a ordem canônica, o vencedor do confronto 2j
        // enfrenta o do 2j+1, como o usuário espera (e como o Pick'em de fase apresenta).
        List<List<Match>> orderedTies = new ArrayList<>(byTie.values());
        for (List<Match> legs : orderedTies) {
            legs.sort(Comparator.comparingInt(Match::getRound)
                    .thenComparing(Match::getCreatedAt));
        }
        orderedTies.sort(Comparator.comparing(legs -> legs.get(0).getCreatedAt()));

        List<Team> winners = new ArrayList<>();
        for (List<Match> legs : orderedTies) {
            Team winner = resolveTieWinner(legs);
            if (winner == null) {
                throw new MatchGenerationException(
                        "Tie " + legs.get(0).getTieId()
                                + " has no winner (draw on aggregate); resolve manually"
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
            for (List<Match> legs : orderedTies) {
                Team loser = resolveTieLoser(legs);
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
            created.add(persistMatch(phase, null, nextHomeRound, tieId, home, away, MatchType.REGULAR));
            if (twoLegged) {
                created.add(persistMatch(phase, null, nextReturnRound, tieId, away, home, MatchType.REGULAR));
            }
        }
        if (isFinalRound && finalLoserA != null && finalLoserB != null) {
            UUID tieId = UUID.randomUUID();
            created.add(persistMatch(phase, null, nextHomeRound, tieId, finalLoserA, finalLoserB, MatchType.THIRD_PLACE));
            if (twoLegged) {
                created.add(persistMatch(phase, null, nextReturnRound, tieId, finalLoserB, finalLoserA, MatchType.THIRD_PLACE));
            }
        }
        return created;
    }

    private Team resolveTieWinner(List<Match> legs) {
        return tieCalculator.compute(legs).winner();
    }

    private Team resolveTieLoser(List<Match> legs) {
        return tieCalculator.loserOf(tieCalculator.compute(legs));
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
            Team away,
            MatchType matchType
    ) {
        Match match = Match.builder()
                .phase(phase)
                .group(group)
                .round(round)
                .tieId(tieId)
                .homeTeam(home)
                .awayTeam(away)
                .status(MatchStatus.SCHEDULED)
                .matchType(matchType)
                .build();
        return matchRepository.save(match);
    }
}
