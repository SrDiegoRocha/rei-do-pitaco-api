package com.example.reidopitaco.service;

import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.enums.BracketMode;
import com.example.reidopitaco.enums.MatchType;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.exception.InvalidMatchException;
import com.example.reidopitaco.repository.MatchRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Garante que a criação/edição <b>manual</b> de partidas em fase KNOCKOUT com
 * {@code bracketMode = FIXED_BRACKET} respeite o chaveamento: nas rodadas seguintes à primeira,
 * cada confronto REGULAR só pode ser formado pelos vencedores de dois confrontos <b>adjacentes</b>
 * da rodada anterior (ordem canônica de criação: {@code 2j} × {@code 2j+1} — a mesma usada pela
 * geração automática e pelo bracket), e a disputa de 3º lugar só pode parear os perdedores das
 * duas semifinais. Em {@code REDRAW_EACH_ROUND} (sem chaveamento) e fora de KNOCKOUT nada é
 * validado — a montagem manual é livre.
 */
@Component
public class BracketIntegrityValidator {

    private final MatchRepository matchRepository;
    private final TieAggregateCalculator tieCalculator;

    public BracketIntegrityValidator(
            MatchRepository matchRepository,
            TieAggregateCalculator tieCalculator
    ) {
        this.matchRepository = matchRepository;
        this.tieCalculator = tieCalculator;
    }

    /**
     * Valida a partida contra o chaveamento fixo da fase. {@code tieId} é o do request (ou o da
     * partida sendo editada); {@code excludeMatchId} exclui a própria partida no update. A 1ª
     * rodada é sempre livre — é ela que define o chaveamento.
     */
    public void validate(
            TournamentPhase phase,
            Team home,
            Team away,
            int round,
            UUID tieId,
            MatchType matchType,
            Long excludeMatchId
    ) {
        if (phase.getPhaseType() != TournamentPhaseType.KNOCKOUT
                || phase.getEffectiveBracketMode() != BracketMode.FIXED_BRACKET) {
            return;
        }
        List<Match> existing = new ArrayList<>(
                matchRepository.findAllByPhasePublicId(phase.getPublicId())
        );
        if (excludeMatchId != null) {
            existing.removeIf(m -> m.getId().equals(excludeMatchId));
        }
        if (existing.isEmpty()) {
            return;
        }

        Map<UUID, List<Match>> byTie = new LinkedHashMap<>();
        for (Match m : existing) {
            byTie.computeIfAbsent(m.getTieId(), k -> new ArrayList<>()).add(m);
        }
        // Segunda perna de um confronto que já existe: o par foi validado na criação da 1ª perna
        // (a consistência entre pernas — times invertidos, rodadas distintas — é validada à parte).
        if (tieId != null && byTie.containsKey(tieId)) {
            return;
        }

        List<TieInfo> regularTies = new ArrayList<>();
        for (List<Match> legs : byTie.values()) {
            legs.sort(Comparator.comparingInt(Match::getRound).thenComparing(Match::getCreatedAt));
            if (legs.get(0).getMatchType() == MatchType.REGULAR) {
                regularTies.add(TieInfo.of(legs, tieCalculator));
            }
        }

        // Rodada anterior (por confronto): maior minRound estritamente menor que o round pedido.
        // Sem rodada anterior = a partida pertence à 1ª rodada, onde o chaveamento é definido.
        int prevStageRound = regularTies.stream()
                .mapToInt(TieInfo::minRound)
                .filter(r -> r < round)
                .max()
                .orElse(-1);
        if (prevStageRound < 0) {
            return;
        }
        List<TieInfo> prevStage = regularTies.stream()
                .filter(t -> t.minRound() == prevStageRound)
                .sorted(Comparator.comparing(TieInfo::firstCreatedAt))
                .toList();

        if (matchType == MatchType.THIRD_PLACE) {
            validateThirdPlace(prevStage, home, away);
            return;
        }
        if (prevStage.size() == 1) {
            throw new InvalidMatchException(
                    "The bracket already has a champion; no further matches can be created"
            );
        }
        int homeIndex = requireWinnerIndex(prevStage, home, "home team");
        int awayIndex = requireWinnerIndex(prevStage, away, "away team");
        // Adjacência canônica: o vencedor do confronto 2j só pode enfrentar o do 2j+1.
        if ((homeIndex ^ 1) != awayIndex) {
            int partnerIndex = homeIndex ^ 1;
            String expected = partnerIndex < prevStage.size()
                    ? "the winner of '" + prevStage.get(partnerIndex).label() + "'"
                    : "a nonexistent adjacent tie (odd bracket)";
            throw new InvalidMatchException(
                    "This match violates the fixed bracket: the winner of '"
                            + prevStage.get(homeIndex).label() + "' must face " + expected
            );
        }
    }

    private void validateThirdPlace(List<TieInfo> prevStage, Team home, Team away) {
        if (prevStage.size() != 2) {
            throw new InvalidMatchException(
                    "THIRD_PLACE match requires exactly 2 semifinal ties in the previous round (found "
                            + prevStage.size() + ")"
            );
        }
        Team loserA = prevStage.get(0).loser();
        Team loserB = prevStage.get(1).loser();
        if (loserA == null || loserB == null) {
            throw new InvalidMatchException(
                    "Semifinal ties must be decided before creating the THIRD_PLACE match"
            );
        }
        Set<Long> expected = Set.of(loserA.getId(), loserB.getId());
        Set<Long> requested = Set.of(home.getId(), away.getId());
        if (!expected.equals(requested)) {
            throw new InvalidMatchException(
                    "THIRD_PLACE match must pair the losers of the two semifinal ties ('"
                            + loserA.getName() + "' and '" + loserB.getName() + "')"
            );
        }
    }

    private int requireWinnerIndex(List<TieInfo> prevStage, Team team, String label) {
        for (int i = 0; i < prevStage.size(); i++) {
            TieInfo tie = prevStage.get(i);
            if (tie.contains(team)) {
                if (tie.winner() == null) {
                    throw new InvalidMatchException(
                            "Previous-round tie '" + tie.label()
                                    + "' has no winner yet; finish it (or set penalties) before scheduling the next round"
                    );
                }
                if (!tie.winner().getId().equals(team.getId())) {
                    throw new InvalidMatchException(
                            label + " '" + team.getName()
                                    + "' did not win their previous-round tie and cannot advance in a fixed bracket"
                    );
                }
                return i;
            }
        }
        throw new InvalidMatchException(
                label + " '" + team.getName()
                        + "' did not come from the previous round; a fixed bracket does not allow new teams to enter mid-phase"
        );
    }

    private record TieInfo(
            Team homeTeam,
            Team awayTeam,
            int minRound,
            Instant firstCreatedAt,
            Team winner
    ) {
        static TieInfo of(List<Match> legs, TieAggregateCalculator calculator) {
            Match first = legs.get(0);
            return new TieInfo(
                    first.getHomeTeam(),
                    first.getAwayTeam(),
                    first.getRound(),
                    first.getCreatedAt(),
                    calculator.compute(legs).winner()
            );
        }

        boolean contains(Team team) {
            return homeTeam.getId().equals(team.getId()) || awayTeam.getId().equals(team.getId());
        }

        String label() {
            return homeTeam.getName() + " x " + awayTeam.getName();
        }

        Team loser() {
            if (winner == null) {
                return null;
            }
            return winner.getId().equals(homeTeam.getId()) ? awayTeam : homeTeam;
        }
    }
}
