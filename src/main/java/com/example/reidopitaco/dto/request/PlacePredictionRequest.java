package com.example.reidopitaco.dto.request;

import com.example.reidopitaco.enums.MatchSide;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Palpite de placar. {@code penaltyWinner} (opcional) só se aplica a confronto de mata-mata
 * que pode ir aos pênaltis — jogo único, ou a perna de volta de ida-e-volta — e somente quando
 * o placar palpitado é empate. Ver regras de validação em {@code PredictionService}.
 */
public record PlacePredictionRequest(
        @NotNull @PositiveOrZero Integer homeScore,
        @NotNull @PositiveOrZero Integer awayScore,
        MatchSide penaltyWinner
) {
}
