package com.example.futbet.dto.response;

import com.example.futbet.enums.TournamentPrivacy;
import com.example.futbet.enums.TournamentStatus;
import com.example.futbet.enums.TournamentType;

import java.time.Instant;
import java.util.UUID;

public record TournamentResponse(
        UUID id,
        String name,
        String description,
        String inviteCode,
        TournamentPrivacy privacy,
        TournamentType type,
        TournamentStatus status,
        Integer maxParticipants,
        Integer maxTeams,
        TournamentOwnerSummary owner,
        TournamentSettingsResponse settings,
        long memberCount,
        long teamCount,
        Instant createdAt,
        Instant updatedAt
) {
    public record TournamentOwnerSummary(UUID id, String name) {
    }
}
