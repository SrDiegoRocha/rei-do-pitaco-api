package com.example.reidopitaco.dto.response;

import com.example.reidopitaco.enums.MatchLegMode;
import com.example.reidopitaco.enums.MatchSide;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.enums.TournamentPrivacy;
import com.example.reidopitaco.enums.TournamentStatus;

import java.util.UUID;

/**
 * Item do feed pessoal de partidas ({@code GET /api/users/me/matches}).
 *
 * <p>Agrega a partida com todo o contexto que o front precisa para montar um card
 * sem fazer chamadas extras: o torneio a que pertence, a fase, o grupo (quando houver)
 * e o palpite do próprio usuário naquela partida. O {@code match} reaproveita o mesmo
 * {@link MatchResponse} dos demais endpoints (times, placar, status, pênaltis), então
 * dados como {@code round}/{@code phaseId}/{@code groupId} também vivem ali dentro — os
 * refs abaixo trazem os <b>nomes/tipos</b> que aquele payload não carrega.
 */
public record UserMatchResponse(
        MatchResponse match,
        TournamentRef tournament,
        PhaseRef phase,
        GroupRef group,                 // null em fases que não são GROUPS
        MyPrediction myPrediction       // null se o usuário ainda não palpitou nesta partida
) {

    public record TournamentRef(
            UUID id,
            String name,
            TournamentPrivacy privacy,   // PUBLIC | PRIVATE
            TournamentStatus status,     // DRAFT | OPEN | IN_PROGRESS | FINISHED
            ScoringRef scoring           // pontuação dos palpites do torneio (para o chip de pontos por faixa)
    ) {
    }

    /**
     * Pontuação de palpite vigente no torneio (de {@code TournamentSettings}). Permite ao
     * front pintar o chip de pontos por faixa (exato/vencedor/erro) também neste feed,
     * sem precisar buscar o torneio inteiro.
     */
    public record ScoringRef(
            int exactScorePoints,        // acertou o placar exato
            int winnerPoints,            // acertou só o vencedor/empate
            int wrongPoints              // errou o desfecho
    ) {
    }

    public record PhaseRef(
            UUID id,
            String name,
            int position,                // ordem da fase no torneio (0-based)
            TournamentPhaseType phaseType, // ROUND_ROBIN | KNOCKOUT | GROUPS
            MatchLegMode matchLegMode    // SINGLE | TWO_LEGGED
    ) {
    }

    public record GroupRef(
            UUID id,
            String name,
            int position
    ) {
    }

    /**
     * Palpite do usuário autenticado nesta partida. Por ser o palpite <b>próprio</b>,
     * nunca é redigido — diferente da visibilidade dos palpites alheios.
     */
    public record MyPrediction(
            UUID id,
            int homeScore,
            int awayScore,
            MatchSide penaltyWinner,     // só em palpite de empate em KO; senão null
            int points                   // pontos já apurados (0 enquanto a partida não fecha)
    ) {
    }
}
