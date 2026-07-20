package com.example.reidopitaco.service;

import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.enums.MatchLegMode;
import com.example.reidopitaco.enums.MatchType;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.repository.MatchRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Resolve o modo de pernas <b>efetivo</b> de uma partida. Em fases KNOCKOUT o admin pode escolher
 * um modo próprio para a rodada final ({@code TournamentPhase.finalLegMode}) — que vale para a
 * final e para a disputa de 3º lugar — independentemente do {@code matchLegMode} da fase. Fonte
 * única para todas as regras que dependem de "jogo único vs ida-e-volta" no nível da partida
 * (prorrogação, pênaltis, cascata de palpite, {@code MatchResponse.matchLegMode}).
 */
@Component
public class MatchLegModeResolver {

    private final MatchRepository matchRepository;

    public MatchLegModeResolver(MatchRepository matchRepository) {
        this.matchRepository = matchRepository;
    }

    /** Modo da rodada final da fase: {@code finalLegMode} se definido, senão o modo da fase. */
    public MatchLegMode finalRoundLegMode(TournamentPhase phase) {
        return phase.getFinalLegMode() != null ? phase.getFinalLegMode() : phase.getMatchLegMode();
    }

    /**
     * Modo efetivo do confronto desta partida. Sem override configurado (ou override igual ao
     * modo da fase), devolve o modo da fase sem nenhuma query; com override, verifica se a
     * partida pertence à rodada final (3º lugar sempre pertence; a final é o único confronto
     * REGULAR da última rodada ordinal esperada da árvore).
     */
    public MatchLegMode effectiveLegMode(Match match) {
        TournamentPhase phase = match.getPhase();
        MatchLegMode override = phase.getFinalLegMode();
        if (phase.getPhaseType() != TournamentPhaseType.KNOCKOUT
                || override == null
                || override == phase.getMatchLegMode()) {
            return phase.getMatchLegMode();
        }
        return isFinalRoundMatch(match, phase) ? override : phase.getMatchLegMode();
    }

    /**
     * A partida pertence à rodada final da fase? {@code THIRD_PLACE} sempre pertence. Para
     * REGULAR: agrupa os confrontos da fase por {@code tieId}, deriva as rodadas ordinais pelo
     * menor round de cada confronto (ida+volta contam como uma rodada) e compara com o total de
     * rodadas esperado da árvore ({@code log2(K)+1} a partir dos K confrontos da 1ª rodada) — o
     * confronto desta partida é a final quando está na rodada ordinal final e é o único REGULAR
     * dela.
     */
    private boolean isFinalRoundMatch(Match match, TournamentPhase phase) {
        if (match.getMatchType() == MatchType.THIRD_PLACE) {
            return true;
        }
        List<Match> all = matchRepository.findAllByPhasePublicId(phase.getPublicId());

        Map<UUID, Integer> minRoundByTie = new HashMap<>();
        for (Match m : all) {
            if (m.getMatchType() != MatchType.REGULAR) {
                continue;
            }
            minRoundByTie.merge(m.getTieId(), m.getRound(), Math::min);
        }

        // Rodadas ordinais: minRounds distintos em ordem crescente.
        TreeMap<Integer, List<UUID>> tiesByMinRound = new TreeMap<>();
        for (Map.Entry<UUID, Integer> entry : minRoundByTie.entrySet()) {
            tiesByMinRound.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        if (tiesByMinRound.isEmpty()) {
            return false;
        }
        int firstRoundTieCount = tiesByMinRound.firstEntry().getValue().size();
        int totalRounds = PhasePredictionContextService.totalRoundsFor(firstRoundTieCount);

        Integer matchTieMinRound = minRoundByTie.get(match.getTieId());
        if (matchTieMinRound == null) {
            return false;
        }
        int ordinal = tiesByMinRound.headMap(matchTieMinRound, true).size();
        List<UUID> tiesInRound = tiesByMinRound.get(matchTieMinRound);
        return ordinal == totalRounds && tiesInRound.size() == 1;
    }
}
