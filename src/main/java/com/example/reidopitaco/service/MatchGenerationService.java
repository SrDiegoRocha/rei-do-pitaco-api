package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.response.MatchResponse;
import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.PhaseGroup;
import com.example.reidopitaco.entity.PhaseTeam;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.enums.BracketMode;
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
    private final MatchLegModeResolver legModeResolver;
    private final SecureRandom random = new SecureRandom();

    public MatchGenerationService(
            TournamentRepository tournamentRepository,
            TournamentPhaseRepository phaseRepository,
            PhaseGroupRepository groupRepository,
            PhaseTeamRepository phaseTeamRepository,
            MatchRepository matchRepository,
            MatchMapper matchMapper,
            TieAggregateCalculator tieCalculator,
            MatchLegModeResolver legModeResolver
    ) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.groupRepository = groupRepository;
        this.phaseTeamRepository = phaseTeamRepository;
        this.matchRepository = matchRepository;
        this.matchMapper = matchMapper;
        this.tieCalculator = tieCalculator;
        this.legModeResolver = legModeResolver;
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
        // Chaveamento fixo exige a árvore completa (potência de 2). Sem chaveamento
        // (REDRAW_EACH_ROUND) basta ser par — cada rodada é sorteada de novo, então a fase pode
        // começar com 24, 40... times (as rodadas seguintes exigem nº par de vencedores).
        if (phase.getEffectiveBracketMode() == BracketMode.FIXED_BRACKET) {
            if (!isPowerOfTwo(n)) {
                throw new MatchGenerationException(
                        "KNOCKOUT requires a power of 2 of teams (got " + n + ")"
                );
            }
        } else if (n % 2 != 0) {
            throw new MatchGenerationException(
                    "KNOCKOUT with REDRAW_EACH_ROUND requires an even number of teams (got " + n + ")"
            );
        }
        if (n < 2) {
            throw new MatchGenerationException("KNOCKOUT needs at least 2 teams");
        }
        Collections.shuffle(teams, random);
        // Com 2 times, a 1ª rodada JÁ é a final — vale o modo próprio da final, se configurado.
        boolean twoLegged = n == 2
                ? legModeResolver.finalRoundLegMode(phase) == MatchLegMode.TWO_LEGGED
                : phase.getMatchLegMode() == MatchLegMode.TWO_LEGGED;

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

    /**
     * Gera a próxima rodada a partir dos vencedores da última rodada existente. A detecção da
     * "última rodada" é por <b>confronto</b> (pernas agrupadas por {@code tieId}, rodada = menor
     * round do confronto) — robusta a modos de perna mistos, já que a rodada final pode ter modo
     * próprio ({@code finalLegMode}), que vale também para a disputa de 3º lugar. A ordem dos
     * vencedores é a canônica do bracket (criação da 1ª perna): o vencedor do confronto {@code 2j}
     * enfrenta o do {@code 2j+1}. Em {@code REDRAW_EACH_ROUND}, os vencedores passam por um novo
     * sorteio (shuffle) antes do emparelhamento — cada rodada tem seu proprio sorteio.
     */
    private List<Match> generateKnockoutNextRound(TournamentPhase phase, List<Match> existing) {
        // Agrupa por confronto; só REGULAR conta para vencedores/rodadas (a disputa de 3º lugar
        // convive com a final e não alimenta rodada seguinte).
        Map<UUID, List<Match>> byTie = new HashMap<>();
        for (Match m : existing) {
            byTie.computeIfAbsent(m.getTieId(), k -> new ArrayList<>()).add(m);
        }
        List<List<Match>> regularTies = new ArrayList<>();
        for (List<Match> legs : byTie.values()) {
            legs.sort(Comparator.comparingInt(Match::getRound)
                    .thenComparing(Match::getCreatedAt));
            if (legs.get(0).getMatchType() == MatchType.REGULAR) {
                regularTies.add(legs);
            }
        }

        int lastMinRound = regularTies.stream()
                .mapToInt(legs -> legs.get(0).getRound())
                .max()
                .orElse(0);
        List<List<Match>> lastRoundTies = regularTies.stream()
                .filter(legs -> legs.get(0).getRound() == lastMinRound)
                .sorted(Comparator.comparing(legs -> legs.get(0).getCreatedAt()))
                .toList();

        // Rodada com um único confronto REGULAR = a final já existe; nada mais a gerar.
        if (lastRoundTies.size() == 1) {
            throw new MatchGenerationException("Phase already has a champion; no more rounds to generate");
        }
        for (List<Match> legs : lastRoundTies) {
            for (Match m : legs) {
                if (m.getStatus() == MatchStatus.SCHEDULED) {
                    throw new MatchGenerationException("Previous round still has unfinished matches");
                }
            }
        }

        List<Team> winners = new ArrayList<>();
        for (List<Match> legs : lastRoundTies) {
            Team winner = resolveTieWinner(legs);
            if (winner == null) {
                throw new MatchGenerationException(
                        "Tie " + legs.get(0).getTieId()
                                + " has no winner (draw on aggregate); resolve manually"
                );
            }
            winners.add(winner);
        }
        if (winners.size() % 2 != 0) {
            throw new MatchGenerationException(
                    "KNOCKOUT requires an even number of winners to pair (got " + winners.size() + ")"
            );
        }

        // Sem chaveamento (REDRAW_EACH_ROUND): os vencedores são sorteados de novo a cada rodada
        // — quem joga contra quem sai deste shuffle, não da posição na árvore. Com chaveamento
        // fixo, a ordem canônica acima é mantida (vencedor do confronto 2j × 2j+1).
        if (phase.getEffectiveBracketMode() == BracketMode.REDRAW_EACH_ROUND) {
            Collections.shuffle(winners, random);
        }

        // A próxima rodada é a final quando restam exatamente 2 vencedores — ela (e o 3º lugar)
        // segue o finalLegMode configurado; as demais rodadas seguem o matchLegMode da fase.
        boolean nextIsFinal = winners.size() == 2;
        boolean twoLegged = nextIsFinal
                ? legModeResolver.finalRoundLegMode(phase) == MatchLegMode.TWO_LEGGED
                : phase.getMatchLegMode() == MatchLegMode.TWO_LEGGED;

        Team finalLoserA = null;
        Team finalLoserB = null;
        boolean withThirdPlace = nextIsFinal && phase.isHasThirdPlace();
        if (withThirdPlace) {
            for (List<Match> legs : lastRoundTies) {
                Team loser = resolveTieLoser(legs);
                if (loser == null) continue;
                if (finalLoserA == null) finalLoserA = loser;
                else finalLoserB = loser;
            }
        }

        int maxRound = existing.stream().mapToInt(Match::getRound).max().orElse(0);
        int nextHomeRound = maxRound + 1;
        int nextReturnRound = maxRound + 2;

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
        if (withThirdPlace && finalLoserA != null && finalLoserB != null) {
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
