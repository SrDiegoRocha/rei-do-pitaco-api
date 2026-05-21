package com.example.futbet.dto.response;

import com.example.futbet.enums.MatchGenerationMode;
import com.example.futbet.enums.MatchLegMode;
import com.example.futbet.enums.TiebreakCriteria;

import java.util.List;

public record TournamentSettingsResponse(
        int winPoints,
        int drawPoints,
        int lossPoints,
        int exactScorePoints,
        int winnerPoints,
        int wrongPoints,
        Integer groupsCount,
        Integer qualifiersPerGroup,
        Boolean playsInsideGroupOnly,
        MatchGenerationMode matchGenerationMode,
        MatchLegMode matchLegMode,
        List<TiebreakCriteria> tiebreakCriteria
) {
}
