package com.example.reidopitaco.enums;

/**
 * Estado do Pick'em de uma fase para o usuário.
 * <ul>
 *   <li>{@code NOT_READY} — a fase ainda não tem o substrato necessário (times/grupos/zonas/bracket)
 *       ou o torneio não está em IN_PROGRESS. Palpite indisponível.</li>
 *   <li>{@code OPEN} — janela aberta: aceita criar/editar/remover o palpite.</li>
 *   <li>{@code LOCKED} — a fase começou (1ª partida iniciou, ou 1º resultado saiu quando não há
 *       horários). Palpites congelados; pontuação passa a correr.</li>
 * </ul>
 */
public enum PickemState {
    NOT_READY,
    OPEN,
    LOCKED
}
