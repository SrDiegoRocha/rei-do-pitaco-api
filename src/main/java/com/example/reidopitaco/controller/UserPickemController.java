package com.example.reidopitaco.controller;

import com.example.reidopitaco.dto.response.PendingPickemResponse;
import com.example.reidopitaco.service.PhasePredictionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Pick'em sob a ótica do usuário autenticado — pendências para o card "Palpitão aberto" da home.
 */
@RestController
@RequestMapping("/api/users/me/pickems")
public class UserPickemController {

    private final PhasePredictionService phasePredictionService;

    public UserPickemController(PhasePredictionService phasePredictionService) {
        this.phasePredictionService = phasePredictionService;
    }

    @GetMapping("/pending")
    public ResponseEntity<List<PendingPickemResponse>> pending(
            @AuthenticationPrincipal String userPublicId
    ) {
        return ResponseEntity.ok(
                phasePredictionService.pendingForUser(UUID.fromString(userPublicId))
        );
    }
}
