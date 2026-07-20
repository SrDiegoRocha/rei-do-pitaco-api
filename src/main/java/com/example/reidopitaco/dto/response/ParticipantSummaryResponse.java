package com.example.reidopitaco.dto.response;

import com.example.reidopitaco.enums.TournamentPhaseType;

import java.util.List;
import java.util.UUID;

/**
 * Perfil do palpiteiro no torneio: desempenho explicado e separado — quanto veio de palpites de
 * partida, quanto veio de Pick'em de fase (com decomposição por fase e por componente). O ranking
 * (§18) mostra só o total somado; o detalhamento mora aqui (D1).
 */
public record ParticipantSummaryResponse(
        UUID userId,
        String userName,
        String avatarUrl,
        Integer rankingPosition,
        int totalPoints,
        int matchPoints,
        int pickemPoints,
        MatchBreakdown matchBreakdown,
        List<PickemPhaseBreakdown> pickemByPhase
) {

    /** Contadores de palpites de partida (mesma base do ranking). */
    public record MatchBreakdown(
            int totalPredictions,
            int exactScoreHits,
            int winnerHits,
            int wrongs
    ) {
    }

    /** Pick'em de uma fase, decomposto por componente de pontuação. */
    public record PickemPhaseBreakdown(
            UUID phaseId,
            String phaseName,
            TournamentPhaseType phaseType,
            boolean provisional,
            int points,
            Components components
    ) {
    }

    public record Components(
            int qualifier,
            int exactPosition,
            int firstPlace,
            int koMatchupExact,
            int koMatchupPartial,
            int champion,
            int runnerUp,
            int thirdPlace
    ) {
    }
}
