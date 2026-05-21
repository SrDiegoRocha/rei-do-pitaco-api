package com.example.futbet.dto.response;

import java.util.UUID;

public record RankingRowResponse(
        int position,
        UUID userId,
        String name,
        String avatarUrl,
        int totalPoints,
        int exactScoreHits,
        int winnerHits,
        int wrongs,
        int totalPredictions
) {
}
