package com.example.reidopitaco.dto.request;

import com.example.reidopitaco.enums.MatchType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Upsert do Pick'em de fase. Exatamente uma das listas se aplica, conforme o tipo da fase:
 * {@code positions} em ROUND_ROBIN/GROUPS, {@code ties} em KNOCKOUT. Palpite parcial é permitido —
 * slots não enviados simplesmente não pontuam.
 */
public record PlacePhasePredictionRequest(
        @Valid List<PositionPick> positions,
        @Valid List<TiePick> ties
) {

    /**
     * Um slot da tabela prevista: {@code teamId} na posição {@code position} do bloco.
     * {@code groupId} é obrigatório em fase GROUPS e proibido em ROUND_ROBIN.
     */
    public record PositionPick(
            UUID groupId,
            @NotNull UUID teamId,
            @NotNull @Min(1) Integer position
    ) {
    }

    /**
     * Um slot do chaveamento previsto: o par de times esperado em {@code (roundNumber, slotIndex)}
     * e quem avança. {@code matchType} default {@code REGULAR}; a disputa de 3º lugar é um slot
     * {@code THIRD_PLACE} na rodada da final.
     */
    public record TiePick(
            @NotNull @Min(1) Integer roundNumber,
            @NotNull @Min(0) Integer slotIndex,
            MatchType matchType,
            @NotNull UUID homeTeamId,
            @NotNull UUID awayTeamId,
            @NotNull UUID winnerTeamId
    ) {
    }
}
