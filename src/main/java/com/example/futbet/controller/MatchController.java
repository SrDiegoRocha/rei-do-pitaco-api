package com.example.futbet.controller;

import com.example.futbet.dto.request.CreateMatchRequest;
import com.example.futbet.dto.request.SetMatchResultRequest;
import com.example.futbet.dto.request.UpdateMatchRequest;
import com.example.futbet.dto.response.MatchResponse;
import com.example.futbet.service.MatchGenerationService;
import com.example.futbet.service.MatchService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/tournaments/{tournamentId}/phases/{phaseId}/matches")
public class MatchController {

    private final MatchService matchService;
    private final MatchGenerationService matchGenerationService;

    public MatchController(
            MatchService matchService,
            MatchGenerationService matchGenerationService
    ) {
        this.matchService = matchService;
        this.matchGenerationService = matchGenerationService;
    }

    @PostMapping("/generate")
    public ResponseEntity<List<MatchResponse>> generate(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId
    ) {
        List<MatchResponse> response = matchGenerationService.generate(
                UUID.fromString(ownerPublicId), tournamentId, phaseId
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping
    public ResponseEntity<MatchResponse> create(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId,
            @Valid @RequestBody CreateMatchRequest request
    ) {
        MatchResponse response = matchService.create(
                UUID.fromString(ownerPublicId), tournamentId, phaseId, request
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<MatchResponse>> list(
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId,
            @RequestParam(required = false) Integer round,
            @RequestParam(required = false) UUID groupId
    ) {
        return ResponseEntity.ok(matchService.list(tournamentId, phaseId, round, groupId));
    }

    @GetMapping("/{matchId}")
    public ResponseEntity<MatchResponse> getById(
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId,
            @PathVariable UUID matchId
    ) {
        return ResponseEntity.ok(matchService.getById(tournamentId, phaseId, matchId));
    }

    @PutMapping("/{matchId}")
    public ResponseEntity<MatchResponse> update(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId,
            @PathVariable UUID matchId,
            @Valid @RequestBody UpdateMatchRequest request
    ) {
        return ResponseEntity.ok(
                matchService.update(UUID.fromString(ownerPublicId), tournamentId, phaseId, matchId, request)
        );
    }

    @PutMapping("/{matchId}/result")
    public ResponseEntity<MatchResponse> setResult(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId,
            @PathVariable UUID matchId,
            @Valid @RequestBody SetMatchResultRequest request
    ) {
        return ResponseEntity.ok(
                matchService.setResult(UUID.fromString(ownerPublicId), tournamentId, phaseId, matchId, request)
        );
    }

    @PutMapping("/{matchId}/cancel")
    public ResponseEntity<MatchResponse> cancel(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId,
            @PathVariable UUID matchId
    ) {
        return ResponseEntity.ok(
                matchService.cancel(UUID.fromString(ownerPublicId), tournamentId, phaseId, matchId)
        );
    }

    @DeleteMapping("/{matchId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID phaseId,
            @PathVariable UUID matchId
    ) {
        matchService.delete(UUID.fromString(ownerPublicId), tournamentId, phaseId, matchId);
        return ResponseEntity.noContent().build();
    }
}
