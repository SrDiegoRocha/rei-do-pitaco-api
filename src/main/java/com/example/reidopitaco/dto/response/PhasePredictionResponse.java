package com.example.reidopitaco.dto.response;

import com.example.reidopitaco.enums.MatchType;
import com.example.reidopitaco.enums.PickemMatchupOutcome;
import com.example.reidopitaco.enums.TournamentPhaseType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pick'em de fase de um usuário. Sempre visível (sem redação): qualquer requester com acesso vê
 * times, palpites e pontos de todos. Os campos de desfecho ({@code outcome}/{@code terminals})
 * são calculados on-demand contra os resultados reais — vêm {@code null} enquanto não há base
 * (partida/fase sem resultados). {@code provisional = true} enquanto a fase não foi finalizada
 * (a pontuação ainda pode mudar).
 */
public record PhasePredictionResponse(
        UUID id,
        UUID phaseId,
        UUID userId,
        String userName,
        String avatarUrl,
        TournamentPhaseType phaseType,
        int points,
        boolean provisional,
        Instant scoredAt,
        List<PositionRow> positions,
        List<TieRow> ties,
        TerminalOutcome terminals,
        Instant createdAt,
        Instant updatedAt
) {

    /** Slot de tabela previsto, com o desfecho por componente quando já há base de comparação. */
    public record PositionRow(
            UUID groupId,
            String groupName,
            MatchResponse.TeamRef team,
            int predictedPosition,
            PositionOutcome outcome
    ) {
    }

    /**
     * Desfecho de um slot de tabela. Campos {@code null} quando ainda não há base (sem resultados
     * na fase). {@code pointsAwarded} é a soma dos componentes deste slot (provisória até a fase
     * finalizar).
     */
    public record PositionOutcome(
            Boolean qualifiedHit,
            Boolean exactPositionHit,
            Boolean firstPlaceHit,
            Integer pointsAwarded
    ) {
    }

    /** Slot de chaveamento previsto, com o desfecho do confronto quando o real já existe. */
    public record TieRow(
            int roundNumber,
            int slotIndex,
            MatchType matchType,
            MatchResponse.TeamRef homeTeam,
            MatchResponse.TeamRef awayTeam,
            MatchResponse.TeamRef winnerTeam,
            TieOutcome outcome
    ) {
    }

    /**
     * Desfecho de um slot de confronto. {@code matchup} compara o par previsto com o confronto
     * real correspondente da rodada ({@code null} para a rodada 1 — os pares são dados — e
     * enquanto o confronto real não existe). {@code winnerAdvanced} diz se o time que o usuário
     * mandou avançar de fato avançou ({@code null} sem base).
     */
    public record TieOutcome(
            PickemMatchupOutcome matchup,
            Boolean winnerAdvanced,
            Integer pointsAwarded
    ) {
    }

    /**
     * Acertos terminais do Pick'em de mata-mata (campeão/vice/3º). {@code null} nos Pick'ems de
     * tabela; campos internos {@code null} enquanto o desfecho real não existe.
     */
    public record TerminalOutcome(
            Boolean championHit,
            Boolean runnerUpHit,
            Boolean thirdPlaceHit,
            Integer pointsAwarded
    ) {
    }
}
