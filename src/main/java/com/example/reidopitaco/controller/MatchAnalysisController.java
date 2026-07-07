package com.example.reidopitaco.controller;

import com.example.reidopitaco.dto.response.MatchAnalysisResponse;
import com.example.reidopitaco.service.MatchAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Aba "Retrospecto" do detalhe da partida (ver DETAILS.md). Endpoint agregado e sob demanda:
 * um único objeto com contexto na competição, confronto direto, forma recente e desempenho dos
 * palpiteiros. Path sem {@code phaseId} — a partida já resolve a fase internamente, igual às
 * rotas de palpite. Acesso: {@link com.example.reidopitaco.service.TournamentAccessGuard} (owner,
 * member ACTIVE, ou PUBLIC não-DRAFT); senão 404.
 */
@RestController
@RequestMapping("/api/tournaments/{tournamentId}/matches/{matchId}/analysis")
public class MatchAnalysisController {

    private final MatchAnalysisService matchAnalysisService;

    public MatchAnalysisController(MatchAnalysisService matchAnalysisService) {
        this.matchAnalysisService = matchAnalysisService;
    }

    @GetMapping
    public ResponseEntity<MatchAnalysisResponse> analyze(
            @AuthenticationPrincipal String requesterPublicId,
            @PathVariable UUID tournamentId,
            @PathVariable UUID matchId
    ) {
        return ResponseEntity.ok(
                matchAnalysisService.analyze(UUID.fromString(requesterPublicId), tournamentId, matchId)
        );
    }
}
