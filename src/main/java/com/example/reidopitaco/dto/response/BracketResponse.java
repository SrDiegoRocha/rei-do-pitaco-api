package com.example.reidopitaco.dto.response;

import com.example.reidopitaco.enums.BracketMode;

import java.util.List;
import java.util.UUID;

/**
 * Representação do chaveamento (mata-mata) de uma fase KNOCKOUT: rodadas → confrontos → pernas.
 * Cada confronto agrupa as pernas que compartilham o mesmo {@code tieId}, com o placar agregado
 * e o vencedor já calculados. {@code bracketMode} indica se as rodadas formam uma árvore fixa
 * ({@code FIXED_BRACKET} — renderizar como chaveamento) ou se cada rodada é sorteada de novo
 * ({@code REDRAW_EACH_ROUND} — renderizar como lista de rodadas, sem linhas ligando confrontos).
 */
public record BracketResponse(
        UUID phaseId,
        String phaseName,
        BracketMode bracketMode,
        List<BracketRound> rounds
) {
    public record BracketRound(
            int round,        // ordinal sequencial (1 = primeira rodada do mata-mata)
            String name,      // rótulo derivado: "Final", "Semifinals", "Quarterfinals", ...
            List<BracketTie> ties
    ) {
    }

    public record BracketTie(
            UUID tieId,
            MatchResponse.TeamRef homeTeam,
            MatchResponse.TeamRef awayTeam,
            Integer homeAggregate,
            Integer awayAggregate,
            Integer homePenalties,          // null se o confronto não foi pra pênaltis
            Integer awayPenalties,
            MatchResponse.TeamRef winner,   // null enquanto o confronto não tiver vencedor definido
            boolean complete,               // true quando todas as pernas estão resolvidas
            boolean thirdPlace,             // true se for a disputa de 3º lugar
            List<MatchResponse> legs
    ) {
    }
}
