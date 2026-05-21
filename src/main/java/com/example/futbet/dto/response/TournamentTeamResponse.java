package com.example.futbet.dto.response;

import java.time.Instant;
import java.util.UUID;

public record TournamentTeamResponse(
        UUID teamId,
        String name,
        String shortName,
        String badgeUrl,
        String primaryColor,
        String secondaryColor,
        Instant addedAt
) {
}
