package com.example.reidopitaco.dto.response;

/**
 * Resumo do recálculo de pontos de palpites de um torneio (ver
 * {@code PredictionService.recalculateAllPoints}). Retornado quando o owner reaplica as regras
 * de pontuação vigentes a todos os palpites já existentes — útil depois de alterar a pontuação
 * com o torneio em andamento.
 *
 * @param totalMatches        total de partidas no torneio
 * @param matchesProcessed    partidas que tinham ao menos um palpite e foram reavaliadas
 * @param predictionsUpdated  palpites cujo {@code points} efetivamente mudou no recálculo
 */
public record RecalculationResponse(
        int totalMatches,
        int matchesProcessed,
        int predictionsUpdated
) {
}
