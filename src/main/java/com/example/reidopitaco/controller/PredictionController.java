package com.example.reidopitaco.controller;

import com.example.reidopitaco.dto.request.PlacePredictionRequest;
import com.example.reidopitaco.dto.response.PredictionResponse;
import com.example.reidopitaco.dto.response.PredictionStatsResponse;
import com.example.reidopitaco.dto.response.RecalculationResponse;
import com.example.reidopitaco.service.PredictionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/matches/{matchId}/predictions/stats")
    public ResponseEntity<PredictionStatsResponse> stats(
            @AuthenticationPrincipal String requesterPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID matchId
    ) {
        return ResponseEntity.ok(
                predictionService.stats(UUID.fromString(requesterPublicId), tournamentId, matchId)
        );
    }

    @PostMapping("/predictions/recalculate")
    public ResponseEntity<RecalculationResponse> recalculate(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID tournamentId
    ) {
        return ResponseEntity.ok(
                predictionService.recalculateAllPoints(UUID.fromString(ownerPublicId), tournamentId)
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

    @GetMapping("/predictions")
    public ResponseEntity<List<PredictionResponse>> listForUser(
            @AuthenticationPrincipal String requesterPublicId,
            @PathVariable UUID tournamentId,
            @RequestParam UUID userId
    ) {
        return ResponseEntity.ok(
                predictionService.listForUserInTournament(
                        UUID.fromString(requesterPublicId), tournamentId, userId
                )
        );
    }
}
