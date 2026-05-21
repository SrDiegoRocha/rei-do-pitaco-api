package com.example.futbet.controller;

import com.example.futbet.dto.request.CreateTeamRequest;
import com.example.futbet.dto.request.UpdateTeamRequest;
import com.example.futbet.dto.response.TeamResponse;
import com.example.futbet.service.TeamService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @PostMapping
    public ResponseEntity<TeamResponse> create(
            @AuthenticationPrincipal String ownerPublicId,
            @Valid @RequestBody CreateTeamRequest request
    ) {
        TeamResponse response = teamService.create(UUID.fromString(ownerPublicId), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<TeamResponse>> list(
            @AuthenticationPrincipal String ownerPublicId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(teamService.list(UUID.fromString(ownerPublicId), pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TeamResponse> getById(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(teamService.getById(UUID.fromString(ownerPublicId), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TeamResponse> update(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTeamRequest request
    ) {
        return ResponseEntity.ok(teamService.update(UUID.fromString(ownerPublicId), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID id
    ) {
        teamService.delete(UUID.fromString(ownerPublicId), id);
        return ResponseEntity.noContent().build();
    }
}
