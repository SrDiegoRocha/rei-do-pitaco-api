package com.example.futbet.controller;

import com.example.futbet.dto.request.ChangeStatusRequest;
import com.example.futbet.dto.request.CreateTournamentRequest;
import com.example.futbet.dto.request.JoinTournamentRequest;
import com.example.futbet.dto.request.UpdateTournamentRequest;
import com.example.futbet.dto.response.TournamentResponse;
import com.example.futbet.service.TournamentMemberService;
import com.example.futbet.service.TournamentService;
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
@RequestMapping("/api/tournaments")
public class TournamentController {

    private final TournamentService tournamentService;
    private final TournamentMemberService memberService;

    public TournamentController(
            TournamentService tournamentService,
            TournamentMemberService memberService
    ) {
        this.tournamentService = tournamentService;
        this.memberService = memberService;
    }

    @PostMapping
    public ResponseEntity<TournamentResponse> create(
            @AuthenticationPrincipal String ownerPublicId,
            @Valid @RequestBody CreateTournamentRequest request
    ) {
        TournamentResponse response = tournamentService.create(UUID.fromString(ownerPublicId), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/mine")
    public ResponseEntity<Page<TournamentResponse>> listMine(
            @AuthenticationPrincipal String ownerPublicId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(tournamentService.listMine(UUID.fromString(ownerPublicId), pageable));
    }

    @GetMapping("/public")
    public ResponseEntity<Page<TournamentResponse>> listPublic(
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(tournamentService.listPublic(pageable));
    }

    @GetMapping("/joined")
    public ResponseEntity<Page<TournamentResponse>> listJoined(
            @AuthenticationPrincipal String userPublicId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(tournamentService.listJoined(UUID.fromString(userPublicId), pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TournamentResponse> getById(
            @AuthenticationPrincipal String requesterPublicId,
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(tournamentService.getById(UUID.fromString(requesterPublicId), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TournamentResponse> update(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTournamentRequest request
    ) {
        return ResponseEntity.ok(tournamentService.update(UUID.fromString(ownerPublicId), id, request));
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<TournamentResponse> changeStatus(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID id,
            @Valid @RequestBody ChangeStatusRequest request
    ) {
        TournamentResponse response = tournamentService.changeStatus(
                UUID.fromString(ownerPublicId),
                id,
                request.targetStatus()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/invite-code/regenerate")
    public ResponseEntity<TournamentResponse> regenerateInviteCode(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(tournamentService.regenerateInviteCode(UUID.fromString(ownerPublicId), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID id
    ) {
        tournamentService.delete(UUID.fromString(ownerPublicId), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/join")
    public ResponseEntity<TournamentResponse> join(
            @AuthenticationPrincipal String userPublicId,
            @Valid @RequestBody JoinTournamentRequest request
    ) {
        TournamentResponse response = memberService.joinByCode(
                UUID.fromString(userPublicId),
                request.inviteCode()
        );
        return ResponseEntity.ok(response);
    }
}
