package com.example.futbet.dto.response;

import com.example.futbet.enums.MatchStatus;

import java.time.Instant;
import java.util.UUID;

public record MatchResponse(
        UUID id,
        UUID phaseId,
        UUID groupId,
        String groupName,
        int round,
        UUID tieId,
        TeamRef homeTeam,
        TeamRef awayTeam,
        Instant scheduledAt,
        Integer homeScore,
        Integer awayScore,
        MatchStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public record TeamRef(UUID id, String name, String shortName, String badgeUrl) {
    }
}
