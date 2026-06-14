package com.example.reidopitaco.controller;

import com.example.reidopitaco.dto.response.PendingPredictionsCountResponse;
import com.example.reidopitaco.dto.response.UserMatchResponse;
import com.example.reidopitaco.service.UserMatchFeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Feed pessoal de partidas do usuário autenticado — base da tela inicial do app.
 */
@RestController
@RequestMapping("/api/users/me/matches")
public class UserMatchController {

    private final UserMatchFeedService userMatchFeedService;

    public UserMatchController(UserMatchFeedService userMatchFeedService) {
        this.userMatchFeedService = userMatchFeedService;
    }

    @GetMapping
    public ResponseEntity<List<UserMatchResponse>> listMine(
            @AuthenticationPrincipal String userPublicId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer limit
    ) {
        return ResponseEntity.ok(
                userMatchFeedService.listForUser(UUID.fromString(userPublicId), from, to, limit)
        );
    }

    @GetMapping("/pending-count")
    public ResponseEntity<PendingPredictionsCountResponse> pendingCount(
            @AuthenticationPrincipal String userPublicId
    ) {
        return ResponseEntity.ok(
                userMatchFeedService.pendingPredictionsCount(UUID.fromString(userPublicId))
        );
    }
}
