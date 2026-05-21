package com.example.futbet.dto.request;

import com.example.futbet.enums.ZoneSelectionMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateZoneRequest(
        @NotBlank @Size(min = 1, max = 60) String name,
        @NotNull @Min(1) Integer fromPosition,
        @NotNull @Min(1) Integer toPosition,
        @NotNull ZoneSelectionMode selectionMode,
        @Min(1) Integer bestRankedCount,
        UUID nextPhaseId
) {
}
