package com.example.reidopitaco.controller;

import com.example.reidopitaco.dto.request.PlacePhasePredictionRequest;
import com.example.reidopitaco.dto.response.PhasePredictionResponse;
import com.example.reidopitaco.dto.response.PhasePredictionStatsResponse;
import com.example.reidopitaco.dto.response.PhasePredictionTemplateResponse;
import com.example.reidopitaco.dto.response.PickemRecalculationResponse;
import com.example.reidopitaco.service.PhasePredictionService;
import com.example.reidopitaco.service.PhasePredictionStatsService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/tournaments/{tournamentId}/phases/{phaseId}/pickem")
public class PhasePredictionController {

    private final PhasePredictionService phasePredictionService;
    private final PhasePredictionStatsService statsService;

    public PhasePredictionController(
            PhasePredictionService phasePredictionService,
            PhasePredictionStatsService statsService
    ) {
        this.phasePredictionService = phasePredictionService;
        this.statsService = statsService;
    }

    @GetMapping("/template")
    public ResponseEntity<PhasePredictionTemplateResponse> template(
            @AuthenticationPrincipal String requesterPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId
    ) {
        return ResponseEntity.ok(
                phasePredictionService.template(UUID.fromString(requesterPublicId), tournamentId, phaseId)
        );
    }

    @PutMapping("/me")
    public ResponseEntity<PhasePredictionResponse> place(
            @AuthenticationPrincipal String userPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId,
            @Valid @RequestBody PlacePhasePredictionRequest request
    ) {
        return ResponseEntity.ok(
                phasePredictionService.place(UUID.fromString(userPublicId), tournamentId, phaseId, request)
        );
    }

    @GetMapping("/me")
    public ResponseEntity<PhasePredictionResponse> getMine(
            @AuthenticationPrincipal String userPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId
    ) {
        return ResponseEntity.ok(
                phasePredictionService.getMine(UUID.fromString(userPublicId), tournamentId, phaseId)
        );
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String userPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId
    ) {
        phasePredictionService.delete(UUID.fromString(userPublicId), tournamentId, phaseId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<Page<PhasePredictionResponse>> list(
            @AuthenticationPrincipal String requesterPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(
                phasePredictionService.listForPhase(
                        UUID.fromString(requesterPublicId), tournamentId, phaseId, pageable
                )
        );
    }

    @GetMapping("/stats")
    public ResponseEntity<PhasePredictionStatsResponse> stats(
            @AuthenticationPrincipal String requesterPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId
    ) {
        return ResponseEntity.ok(
                statsService.stats(UUID.fromString(requesterPublicId), tournamentId, phaseId)
        );
    }

    @PostMapping("/recalculate")
    public ResponseEntity<PickemRecalculationResponse> recalculate(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId
    ) {
        return ResponseEntity.ok(
                phasePredictionService.recalculate(UUID.fromString(ownerPublicId), tournamentId, phaseId)
        );
    }

    @GetMapping("/{userId}")
    public ResponseEntity<PhasePredictionResponse> getForUser(
            @AuthenticationPrincipal String requesterPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId,
            @PathVariable UUID userId
    ) {
        return ResponseEntity.ok(
                phasePredictionService.getForUser(
                        UUID.fromString(requesterPublicId), tournamentId, phaseId, userId
                )
        );
    }
}
