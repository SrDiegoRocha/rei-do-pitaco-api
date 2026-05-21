package com.example.futbet.controller;

import com.example.futbet.dto.request.PlacePredictionRequest;
import com.example.futbet.dto.response.PredictionResponse;
import com.example.futbet.service.PredictionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tournaments/{tournamentId}")
public class PredictionController {

    private final PredictionService predictionService;

    public PredictionController(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    @PutMapping("/matches/{matchId}/predictions/me")
    public ResponseEntity<PredictionResponse> place(
            @AuthenticationPrincipal String userPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID matchId,
            @Valid @RequestBody PlacePredictionRequest request
    ) {
        return ResponseEntity.ok(
                predictionService.place(UUID.fromString(userPublicId), tournamentId, matchId, request)
        );
    }

    @DeleteMapping("/matches/{matchId}/predictions/me")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String userPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID matchId
    ) {
        predictionService.delete(UUID.fromString(userPublicId), tournamentId, matchId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/matches/{matchId}/predictions")
    public ResponseEntity<List<PredictionResponse>> listForMatch(
            @AuthenticationPrincipal String requesterPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID matchId
    ) {
        return ResponseEntity.ok(
                predictionService.listForMatch(UUID.fromString(requesterPublicId), tournamentId, matchId)
        );
    }

    @GetMapping("/predictions/me")
    public ResponseEntity<List<PredictionResponse>> listMine(
            @AuthenticationPrincipal String userPublicId,
            @PathVariable UUID tournamentId
    ) {
        return ResponseEntity.ok(
                predictionService.listMine(UUID.fromString(userPublicId), tournamentId)
        );
    }
}
