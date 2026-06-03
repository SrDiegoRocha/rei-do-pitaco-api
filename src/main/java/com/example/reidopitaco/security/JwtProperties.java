package com.example.reidopitaco.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "reidopitaco.security.jwt")
public record JwtProperties(

        // HS256 exige chave HMAC de no mínimo 256 bits (32 bytes). Sem secret válido, a app não sobe.
        @NotBlank(message = "JWT_SECRET must be provided")
        @Size(min = 32, message = "JWT_SECRET must be at least 32 characters (256 bits) long")
        String secret,

        @Positive
        long accessTokenExpirationMinutes,

        @Positive
        long refreshTokenExpirationDays,

        @NotNull
        String issuer
) {
}
