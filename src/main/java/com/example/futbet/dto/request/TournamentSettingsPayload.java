package com.example.futbet.dto.request;

import com.example.futbet.enums.MatchGenerationMode;
import com.example.futbet.enums.MatchLegMode;
import com.example.futbet.enums.TiebreakCriteria;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public record TournamentSettingsPayload(
        @NotNull @PositiveOrZero Integer winPoints,
        @NotNull @PositiveOrZero Integer drawPoints,
        @NotNull @PositiveOrZero Integer lossPoints,

        @NotNull @PositiveOrZero Integer exactScorePoints,
        @NotNull @PositiveOrZero Integer winnerPoints,
        @NotNull @PositiveOrZero Integer wrongPoints,

        @Min(2) Integer groupsCount,
        @Min(1) Integer qualifiersPerGroup,
        Boolean playsInsideGroupOnly,

        @NotNull MatchGenerationMode matchGenerationMode,
        @NotNull MatchLegMode matchLegMode,

        @NotEmpty List<TiebreakCriteria> tiebreakCriteria
) {
}
