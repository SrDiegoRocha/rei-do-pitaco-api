package com.example.reidopitaco.dto.response;

import com.example.reidopitaco.enums.PickemState;
import com.example.reidopitaco.enums.TournamentPhaseType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Estrutura que o front usa para montar a UI do Pick'em de uma fase: estado da janela, pontuação
 * vigente e o "molde" a preencher ({@code table} em ROUND_ROBIN/GROUPS, {@code bracket} em
 * KNOCKOUT — o outro vem {@code null}). Quando {@code state = NOT_READY}, o molde pode vir
 * {@code null} (substrato ainda não existe) e {@code stateReason} explica o porquê.
 */
public record PhasePredictionTemplateResponse(
        UUID phaseId,
        String phaseName,
        TournamentPhaseType phaseType,
        PickemState state,
        String stateReason,
        Instant lockAt,
        PickemScoring scoring,
        TableTemplate table,
        BracketTemplate bracket
) {

    /** Eco da pontuação de Pick'em configurada no torneio ({@code TournamentSettings}). */
    public record PickemScoring(
            int qualifierPoints,
            int exactPositionPoints,
            int firstPlacePoints,
            int koMatchupExactPoints,
            int koMatchupPartialPoints,
            int championPoints,
            int runnerUpPoints,
            int thirdPlacePoints
    ) {
    }

    /**
     * Molde do Pick'em de tabela: para cada bloco (grupo, ou bloco único em ROUND_ROBIN com
     * {@code groupId = null}), os times disponíveis e quantos slots de posição preencher
     * ({@code qualifyingDepth}, derivado das zonas de classificação, limitado ao tamanho do bloco).
     */
    public record TableTemplate(
            int qualifyingDepth,
            List<GroupBlock> groups
    ) {
    }

    public record GroupBlock(
            UUID groupId,
            String groupName,
            int qualifyingDepth,
            List<MatchResponse.TeamRef> teams
    ) {
    }

    /**
     * Molde do Pick'em de chaveamento. A rodada 1 vem com os confrontos reais (pares fixos — o
     * usuário só escolhe quem avança); rodadas seguintes vêm com slots vazios ({@code homeTeam}/
     * {@code awayTeam} = {@code null}) que o front preenche com os vencedores escolhidos
     * (vencedores dos slots {@code 2j} e {@code 2j+1} alimentam o slot {@code j} da rodada
     * seguinte). Se {@code hasThirdPlace}, há um slot extra {@code THIRD_PLACE} na rodada final
     * (slotIndex 0), preenchido com os perdedores previstos das semifinais.
     */
    public record BracketTemplate(
            boolean hasThirdPlace,
            int totalRounds,
            List<TemplateRound> rounds
    ) {
    }

    public record TemplateRound(
            int roundNumber,
            String name,
            List<TemplateSlot> slots
    ) {
    }

    public record TemplateSlot(
            int slotIndex,
            MatchResponse.TeamRef homeTeam,
            MatchResponse.TeamRef awayTeam
    ) {
    }
}
