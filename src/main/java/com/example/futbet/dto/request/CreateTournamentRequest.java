package com.example.futbet.dto.request;

import com.example.futbet.enums.TournamentPrivacy;
import com.example.futbet.enums.TournamentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTournamentRequest(
        @NotBlank @Size(min = 3, max = 80) String name,
        @Size(max = 500) String description,
        @NotNull TournamentPrivacy privacy,
        @NotNull TournamentType type,
        @Min(2) Integer maxParticipants,
        @Min(2) Integer maxTeams,
        @NotNull @Valid TournamentSettingsPayload settings
) {
}
