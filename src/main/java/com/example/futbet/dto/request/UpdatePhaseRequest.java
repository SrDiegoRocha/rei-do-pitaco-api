package com.example.futbet.dto.request;

import com.example.futbet.enums.MatchGenerationMode;
import com.example.futbet.enums.MatchLegMode;
import com.example.futbet.enums.TournamentPhaseType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdatePhaseRequest(
        @NotBlank @Size(min = 1, max = 60) String name,
        @NotNull TournamentPhaseType phaseType,
        @NotNull MatchLegMode matchLegMode,
        @NotNull MatchGenerationMode matchGenerationMode,
        @Min(1) Integer qualifiersPerGroup,
        Boolean playsInsideGroupOnly,
        Boolean hasThirdPlace
) {
}
