package com.example.reidopitaco.dto.response;

/**
 * Contagem de partidas aguardando o palpite do usuário — alimenta o badge
 * "X jogos esperando seu pitaco" ({@code GET /api/users/me/matches/pending-count}).
 */
public record PendingPredictionsCountResponse(
        long count
) {
}
