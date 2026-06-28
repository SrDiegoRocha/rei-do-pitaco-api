package com.example.reidopitaco.controller;

import com.example.reidopitaco.dto.response.RankingRowResponse;
import com.example.reidopitaco.enums.MatchType;
import com.example.reidopitaco.enums.TournamentMemberStatus;
import com.example.reidopitaco.service.RankingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * Ranking do torneio. Filtros opcionais e combináveis: {@code phaseId}, {@code groupId}
     * (exige {@code phaseId}), {@code round}, {@code matchType} (REGULAR/THIRD_PLACE, separa
     * Final da Disputa de 3º na mesma rodada) e {@code memberStatus} (ACTIVE/LEFT/BANNED —
     * restringe o ranking aos membros naquele status). Sem filtro, agrega o torneio inteiro.
     */
    @GetMapping
    public ResponseEntity<List<RankingRowResponse>> ranking(
            @AuthenticationPrincipal String requesterPublicId,
            @PathVariable UUID tournamentId,
            @RequestParam(required = false) UUID phaseId,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) Integer round,
            @RequestParam(required = false) MatchType matchType,
            @RequestParam(required = false) TournamentMemberStatus memberStatus
    ) {
        return ResponseEntity.ok(rankingService.compute(
                UUID.fromString(requesterPublicId), tournamentId, phaseId, groupId, round, matchType, memberStatus));
    }
}
