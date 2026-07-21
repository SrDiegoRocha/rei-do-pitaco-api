package com.example.reidopitaco.controller;

import com.example.reidopitaco.dto.response.MatchResponse;
import com.example.reidopitaco.service.MatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tournaments/{tournamentId}/matches")
public class TournamentMatchController {

    private final MatchService matchService;

    public TournamentMatchController(MatchService matchService) {
        this.matchService = matchService;
    }

    @GetMapping
    public ResponseEntity<List<MatchResponse>> listAll(
            @AuthenticationPrincipal String requesterPublicId,
            @PathVariable UUID tournamentId
    ) {
        return ResponseEntity.ok(
                matchService.listByTournament(UUID.fromString(requesterPublicId), tournamentId)
        );
    }

    @GetMapping("/tie/{tieId}")
    public ResponseEntity<List<MatchResponse>> listByTie(
            @AuthenticationPrincipal String requesterPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID tieId
    ) {
        return ResponseEntity.ok(
                matchService.listByTie(UUID.fromString(requesterPublicId), tournamentId, tieId)
        );
    }
}
