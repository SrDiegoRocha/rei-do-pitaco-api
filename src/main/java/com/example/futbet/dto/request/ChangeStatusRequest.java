package com.example.futbet.dto.request;

import com.example.futbet.enums.TournamentStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeStatusRequest(
        @NotNull TournamentStatus targetStatus
) {
}
