package com.example.futbet.controller;

import com.example.futbet.dto.response.TournamentMemberResponse;
import com.example.futbet.service.TournamentMemberService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/tournaments/{tournamentId}/members")
public class TournamentMemberController {

    private final TournamentMemberService memberService;

    public TournamentMemberController(TournamentMemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    public ResponseEntity<Page<TournamentMemberResponse>> list(
            @PathVariable UUID tournamentId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(memberService.list(tournamentId, pageable));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> leave(
            @AuthenticationPrincipal String userPublicId,
            @PathVariable UUID tournamentId
    ) {
        memberService.leave(UUID.fromString(userPublicId), tournamentId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> ban(
            @AuthenticationPrincipal String ownerPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID userId
    ) {
        memberService.ban(UUID.fromString(ownerPublicId), tournamentId, userId);
        return ResponseEntity.noContent().build();
    }
}
