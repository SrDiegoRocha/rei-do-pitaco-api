package com.example.futbet.dto.response;

import java.time.Instant;
import java.util.UUID;

public record PredictionResponse(
        UUID id,
        UUID matchId,
        UUID userId,
        String userName,
        int homeScore,
        int awayScore,
        int points,
        Instant createdAt,
        Instant updatedAt
) {
}
