package com.example.reidopitaco.controller;

import com.example.reidopitaco.dto.response.ParticipantSummaryResponse;
import com.example.reidopitaco.service.ParticipantSummaryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/tournaments/{tournamentId}/participants")
public class ParticipantSummaryController {

    private final ParticipantSummaryService participantSummaryService;

    public ParticipantSummaryController(ParticipantSummaryService participantSummaryService) {
        this.participantSummaryService = participantSummaryService;
    }

    @GetMapping("/{userId}/summary")
    public ResponseEntity<ParticipantSummaryResponse> summary(
            @AuthenticationPrincipal String requesterPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID userId
    ) {
        return ResponseEntity.ok(
                participantSummaryService.summary(UUID.fromString(requesterPublicId), tournamentId, userId)
        );
    }
}
