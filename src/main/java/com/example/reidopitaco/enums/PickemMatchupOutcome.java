package com.example.reidopitaco.enums;

/**
 * Desfecho de um slot de confronto do Pick'em de mata-mata, comparado com o confronto real:
 * {@code EXACT} = cravou os dois times; {@code PARTIAL} = acertou pelo menos 1;
 * {@code MISS} = nenhum dos dois. A pontuação de EXACT/PARTIAL é exclusiva (paga o maior).
 */
public enum PickemMatchupOutcome {
    EXACT,
    PARTIAL,
    MISS
}
