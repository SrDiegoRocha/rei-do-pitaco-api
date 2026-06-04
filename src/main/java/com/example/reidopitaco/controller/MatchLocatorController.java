package com.example.reidopitaco.controller;

import com.example.reidopitaco.dto.response.MatchLocationResponse;
import com.example.reidopitaco.service.MatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Resolve links curtos de partida (/m/{matchId}) para sua localização completa.
 * Requer apenas autenticação; a checagem de participação fica no detalhe.
 */
@RestController
@RequestMapping("/api/matches")
public class MatchLocatorController {

    private final MatchService matchService;

    public MatchLocatorController(MatchService matchService) {
        this.matchService = matchService;
    }

    @GetMapping("/{matchId}/location")
    public ResponseEntity<MatchLocationResponse> locate(@PathVariable UUID matchId) {
        return ResponseEntity.ok(matchService.locate(matchId));
    }
}
