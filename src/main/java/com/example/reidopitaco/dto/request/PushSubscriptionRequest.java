package com.example.reidopitaco.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Inscrição Web Push enviada pelo navegador (PushSubscription.toJSON()).
 * As chaves vêm em Base64 URL-safe.
 */
public record PushSubscriptionRequest(
        @NotBlank String endpoint,
        @NotBlank String p256dh,
        @NotBlank String auth
) {
}
