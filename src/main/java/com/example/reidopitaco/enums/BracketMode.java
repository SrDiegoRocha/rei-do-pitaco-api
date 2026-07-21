package com.example.reidopitaco.enums;

/**
 * Como os confrontos das rodadas seguintes de uma fase KNOCKOUT são formados.
 *
 * <p>{@code FIXED_BRACKET}: chaveamento fixo — o sorteio da 1ª rodada define a árvore inteira;
 * o vencedor do confronto {@code 2j} enfrenta o do {@code 2j+1} (ordem canônica de criação).
 * A criação/edição manual de partidas é validada contra essa árvore.
 *
 * <p>{@code REDRAW_EACH_ROUND}: sem chaveamento — a cada rodada os vencedores são sorteados de
 * novo para definir quem joga contra quem (estilo fases iniciais da Copa do Brasil). A geração
 * automática embaralha os vencedores; a criação manual é livre.
 */
public enum BracketMode {
    FIXED_BRACKET,
    REDRAW_EACH_ROUND
}
