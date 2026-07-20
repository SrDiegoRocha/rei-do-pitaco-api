package com.example.reidopitaco.dto.response;

import com.example.reidopitaco.enums.TeamType;

import java.time.Instant;
import java.util.UUID;

public record TournamentTeamResponse(
        UUID teamId,
        String name,
        String shortName,
        String badgeUrl,
        String primaryColor,
        String secondaryColor,
        boolean system,
        TeamType teamType,
        String countryCode,
        String leagueSlug,
        String leagueName,
        Instant addedAt
) {
}
