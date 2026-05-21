package com.example.futbet.controller;

import com.example.futbet.dto.response.TournamentTeamResponse;
import com.example.futbet.service.TournamentTeamService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/tournaments/{tournamentId}/teams")
public class TournamentTeamController {

    private final TournamentTeamService teamService;

    public TournamentTeamController(TournamentTeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping
    public ResponseEntity<Page<TournamentTeamResponse>> list(
            @PathVariable UUID tournamentId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(teamService.list(tournamentId, pageable));
    }

    @PostMapping("/{teamId}")
    public ResponseEntity<TournamentTeamResponse> link(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID teamId
    ) {
        TournamentTeamResponse response = teamService.link(
                UUID.fromString(ownerPublicId),
                tournamentId,
                teamId
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{teamId}")
    public ResponseEntity<Void> unlink(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID teamId
    ) {
        teamService.unlink(UUID.fromString(ownerPublicId), tournamentId, teamId);
        return ResponseEntity.noContent().build();
    }
}
