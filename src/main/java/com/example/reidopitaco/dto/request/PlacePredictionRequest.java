package com.example.reidopitaco.dto.request;

import com.example.reidopitaco.enums.MatchSide;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Palpite de placar.
 *
 * <p>Em mata-mata de jogo único, o palpite segue uma cascata obrigatória: palpitou empate no tempo
 * normal → é obrigatório informar o placar da prorrogação ({@code homeExtraTimeScore}/
 * {@code awayExtraTimeScore}, cumulativos e {@code >=} placar do tempo normal); se a prorrogação
 * palpitada também for empate → é obrigatório informar {@code penaltyWinner} (quem passa).
 *
 * <p>{@code penaltyWinner} também se aplica à perna de volta de ida-e-volta empatada no agregado
 * (nesse caso sem prorrogação). Ver regras completas de validação em {@code PredictionService}.
 */
public record PlacePredictionRequest(
        @NotNull @PositiveOrZero Integer homeScore,
        @NotNull @PositiveOrZero Integer awayScore,
        @PositiveOrZero Integer homeExtraTimeScore,
        @PositiveOrZero Integer awayExtraTimeScore,
        MatchSide penaltyWinner
) {
}
