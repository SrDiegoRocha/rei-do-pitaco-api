package com.example.reidopitaco.enums;

/**
 * Lado de um confronto, relativo ao próprio jogo (mandante/visitante daquela partida).
 * Usado no palpite de quem passa nos pênaltis ({@code Prediction.penaltyWinner}): em
 * mata-mata de jogo único, ou na perna de volta de um confronto ida-e-volta.
 */
public enum MatchSide {
    HOME,
    AWAY
}
