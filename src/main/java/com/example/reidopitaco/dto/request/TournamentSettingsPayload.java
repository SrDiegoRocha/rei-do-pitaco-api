package com.example.reidopitaco.dto.request;

import com.example.reidopitaco.enums.TiebreakCriteria;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public record TournamentSettingsPayload(
        @NotNull @PositiveOrZero Integer winPoints,
        @NotNull @PositiveOrZero Integer drawPoints,
        @NotNull @PositiveOrZero Integer lossPoints,

        @NotNull @PositiveOrZero Integer exactScorePoints,
        @NotNull @PositiveOrZero Integer winnerPoints,
        @NotNull @PositiveOrZero Integer wrongPoints,

        // Componentes de mata-mata de jogo único. Opcionais para não quebrar clientes antigos:
        // ausentes (null) => default no create (2/1/2) e preservam o valor atual no update.
        @PositiveOrZero Integer extraTimeExactScorePoints,
        @PositiveOrZero Integer extraTimeWinnerPoints,
        @PositiveOrZero Integer penaltyWinnerPoints,

        @NotEmpty List<TiebreakCriteria> tiebreakCriteria
) {
}
