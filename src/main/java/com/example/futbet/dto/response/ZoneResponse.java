package com.example.futbet.dto.response;

import com.example.futbet.enums.ZoneSelectionMode;

import java.time.Instant;
import java.util.UUID;

public record ZoneResponse(
        UUID id,
        String name,
        int fromPosition,
        int toPosition,
        ZoneSelectionMode selectionMode,
        Integer bestRankedCount,
        UUID nextPhaseId,
        String nextPhaseName,
        int position,
        Instant createdAt,
        Instant updatedAt
) {
}
