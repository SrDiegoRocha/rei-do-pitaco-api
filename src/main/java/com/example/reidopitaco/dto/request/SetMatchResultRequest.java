package com.example.reidopitaco.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SetMatchResultRequest(
        @NotNull @PositiveOrZero Integer homeScore,
        @NotNull @PositiveOrZero Integer awayScore,
        // Prorrogação opcional: só em KNOCKOUT jogo único empatado no tempo normal; ambos juntos e
        // cumulativos (>= placar do tempo normal por time).
        @PositiveOrZero Integer homeExtraTimeScore,
        @PositiveOrZero Integer awayExtraTimeScore,
        // Pênaltis opcionais: só em mata-mata, ambos juntos e diferentes entre si.
        @PositiveOrZero Integer homePenalties,
        @PositiveOrZero Integer awayPenalties
) {
}
