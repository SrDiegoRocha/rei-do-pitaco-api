package com.example.reidopitaco.dto.request;

import com.example.reidopitaco.enums.BracketMode;
import com.example.reidopitaco.enums.MatchGenerationMode;
import com.example.reidopitaco.enums.MatchLegMode;
import com.example.reidopitaco.enums.TournamentPhaseType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreatePhaseRequest(
        @NotBlank @Size(min = 1, max = 60) String name,
        @NotNull TournamentPhaseType phaseType,
        @NotNull MatchLegMode matchLegMode,
        @NotNull MatchGenerationMode matchGenerationMode,
        Boolean playsInsideGroupOnly,
        Boolean hasThirdPlace,
        // Modo de pernas da rodada final (final + 3º lugar). Opcional, só em KNOCKOUT;
        // null = herda o matchLegMode da fase.
        MatchLegMode finalLegMode,
        // Chaveamento fixo vs sorteio por rodada. Opcional, só em KNOCKOUT; null = default
        // (FIXED_BRACKET em AUTOMATIC, REDRAW_EACH_ROUND em MANUAL).
        BracketMode bracketMode
) {
}
