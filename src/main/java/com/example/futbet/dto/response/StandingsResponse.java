package com.example.futbet.dto.response;

import java.util.List;
import java.util.UUID;

public record StandingsResponse(
        UUID phaseId,
        List<GroupStandings> groups
) {
    public record GroupStandings(
            UUID groupId,
            String groupName,
            List<StandingRow> rows
    ) {
    }

    public record StandingRow(
            int position,
            UUID teamId,
            String teamName,
            String shortName,
            String badgeUrl,
            int played,
            int wins,
            int draws,
            int losses,
            int goalsFor,
            int goalsAgainst,
            int goalDifference,
            int points
    ) {
    }
}
