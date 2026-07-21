package com.example.reidopitaco.dto.response;

import com.example.reidopitaco.enums.BracketMode;
import com.example.reidopitaco.enums.MatchGenerationMode;
import com.example.reidopitaco.enums.MatchLegMode;
import com.example.reidopitaco.enums.TournamentPhaseType;

import java.time.Instant;
import java.util.UUID;

public record PhaseResponse(
        UUID id,
        String name,
        int position,
        TournamentPhaseType phaseType,
        MatchLegMode matchLegMode,
        MatchGenerationMode matchGenerationMode,
        Boolean playsInsideGroupOnly,
        boolean hasThirdPlace,
        MatchLegMode finalLegMode,   // modo da rodada final (final + 3º); null = herda matchLegMode
        BracketMode bracketMode,     // só em KNOCKOUT (resolvido, nunca null em KO); null nas demais

        long groupCount,
        long teamCount,
        Instant finalizedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
