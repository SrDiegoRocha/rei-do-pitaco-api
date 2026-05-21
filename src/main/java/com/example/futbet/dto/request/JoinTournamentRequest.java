package com.example.futbet.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinTournamentRequest(
        @NotBlank @Size(min = 8, max = 8) String inviteCode
) {
}
