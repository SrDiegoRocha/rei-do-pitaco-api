package com.example.reidopitaco.dto.response;

import java.util.UUID;

/**
 * Resolve um link curto de partida (/m/{matchId}) para sua localização completa
 * (torneio + fase), permitindo o front montar a rota detalhada.
 */
public record MatchLocationResponse(
        UUID tournamentId,
        UUID phaseId,
        UUID matchId
) {
}
