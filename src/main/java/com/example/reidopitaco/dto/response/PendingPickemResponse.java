package com.example.reidopitaco.dto.response;

import com.example.reidopitaco.enums.TournamentPhaseType;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma fase em que o usuário ainda pode (e ainda não fez) o Pick'em — item do card "Palpitão
 * aberto" da home. {@code lockAt} = quando a janela trava ({@code null} = trava quando o 1º
 * resultado for lançado).
 */
public record PendingPickemResponse(
        UUID tournamentId,
        String tournamentName,
        UUID phaseId,
        String phaseName,
        TournamentPhaseType phaseType,
        Instant lockAt
) {
}
