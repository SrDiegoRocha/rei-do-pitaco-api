package com.example.futbet.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record CreateTeamRequest(
        @NotBlank
        @Size(min = 2, max = 80)
        String name,

        @Size(min = 2, max = 5)
        String shortName,

        @URL
        @Size(max = 500)
        String badgeUrl,

        @NotBlank
        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "must be a hex color in the format #RRGGBB")
        String primaryColor,

        @NotBlank
        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "must be a hex color in the format #RRGGBB")
        String secondaryColor
) {
}
