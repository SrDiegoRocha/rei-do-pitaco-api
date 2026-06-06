package com.example.reidopitaco.controller;

import com.example.reidopitaco.dto.request.PushSubscriptionRequest;
import com.example.reidopitaco.service.PushSubscriptionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Registro/remoção de inscrições Web Push do usuário autenticado.
 */
@RestController
@RequestMapping("/api/push/subscriptions")
public class PushSubscriptionController {

    private final PushSubscriptionService pushSubscriptionService;

    public PushSubscriptionController(PushSubscriptionService pushSubscriptionService) {
        this.pushSubscriptionService = pushSubscriptionService;
    }

    @PostMapping
    public ResponseEntity<Void> subscribe(
            @AuthenticationPrincipal String userPublicId,
            @Valid @RequestBody PushSubscriptionRequest request
    ) {
        pushSubscriptionService.subscribe(UUID.fromString(userPublicId), request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> unsubscribe(
            @AuthenticationPrincipal String userPublicId,
            @RequestParam String endpoint
    ) {
        pushSubscriptionService.unsubscribe(UUID.fromString(userPublicId), endpoint);
        return ResponseEntity.noContent().build();
    }
}
