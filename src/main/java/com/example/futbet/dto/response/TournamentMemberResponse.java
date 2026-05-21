package com.example.futbet.dto.response;

import com.example.futbet.enums.TournamentMemberRole;
import com.example.futbet.enums.TournamentMemberStatus;

import java.time.Instant;
import java.util.UUID;

public record TournamentMemberResponse(
        UUID userId,
        String name,
        String avatarUrl,
        TournamentMemberRole role,
        TournamentMemberStatus status,
        Instant joinedAt,
        Instant leftAt,
        Instant bannedAt
) {
}
