package com.example.reidopitaco.dto.response;

import com.example.reidopitaco.enums.TournamentPhaseType;

import java.util.List;
import java.util.UUID;

/**
 * "Previsão da galera" do Pick'em de uma fase — só contagens e percentuais agregados, nunca
 * palpites individuais (esses estão na listagem, que é sempre visível de qualquer forma).
 * {@code table} preenchido em ROUND_ROBIN/GROUPS; {@code bracket} em KNOCKOUT (o outro nulo).
 */
public record PhasePredictionStatsResponse(
        UUID phaseId,
        TournamentPhaseType phaseType,
        long totalPickems,
        TableStats table,
        BracketStats bracket
) {

    public record TableStats(List<GroupStats> groups) {
    }

    /**
     * Distribuições de um bloco (grupo, ou bloco único em ROUND_ROBIN). {@code pickems} = quantos
     * Pick'ems têm ao menos um palpite neste bloco (base do pct de {@code qualifiers}).
     * {@code firstPlace} soma 100 (escolha única); {@code qualifiers} não soma 100 (cada Pick'em
     * escolhe vários times).
     */
    public record GroupStats(
            UUID groupId,
            String groupName,
            long pickems,
            List<TeamShare> firstPlace,
            List<TeamShare> qualifiers
    ) {
    }

    /** Distribuições de campeão/vice/3º previstos. Cada lista soma 100 sobre quem palpitou. */
    public record BracketStats(
            List<TeamShare> champion,
            List<TeamShare> runnerUp,
            List<TeamShare> thirdPlace
    ) {
    }

    public record TeamShare(
            MatchResponse.TeamRef team,
            long count,
            int pct
    ) {
    }
}
