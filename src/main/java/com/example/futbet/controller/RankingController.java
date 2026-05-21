package com.example.futbet.controller;

import com.example.futbet.dto.response.RankingRowResponse;
import com.example.futbet.service.RankingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tournaments/{tournamentId}/ranking")
public class RankingController {

    private final RankingService rankingService;

    public RankingController(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @GetMapping
    public ResponseEntity<List<RankingRowResponse>> ranking(@PathVariable UUID tournamentId) {
        return ResponseEntity.ok(rankingService.compute(tournamentId));
    }
}
