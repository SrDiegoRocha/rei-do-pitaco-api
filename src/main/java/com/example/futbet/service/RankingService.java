package com.example.futbet.service;

import com.example.futbet.dto.response.RankingRowResponse;
import com.example.futbet.entity.Match;
import com.example.futbet.entity.Prediction;
import com.example.futbet.entity.Tournament;
import com.example.futbet.entity.TournamentSettings;
import com.example.futbet.entity.User;
import com.example.futbet.enums.MatchStatus;
import com.example.futbet.exception.TournamentNotFoundException;
import com.example.futbet.repository.PredictionRepository;
import com.example.futbet.repository.TournamentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RankingService {

    private final TournamentRepository tournamentRepository;
    private final PredictionRepository predictionRepository;

    public RankingService(
            TournamentRepository tournamentRepository,
            PredictionRepository predictionRepository
    ) {
        this.tournamentRepository = tournamentRepository;
        this.predictionRepository = predictionRepository;
    }

    @Transactional(readOnly = true)
    public List<RankingRowResponse> compute(UUID tournamentPublicId) {
        Tournament tournament = tournamentRepository.findByPublicIdAndActiveTrue(tournamentPublicId)
                .orElseThrow(TournamentNotFoundException::new);
        TournamentSettings settings = tournament.getSettings();

        List<Prediction> predictions = predictionRepository.findAllByTournamentPublicId(tournamentPublicId);

        Map<Long, Accumulator> table = new HashMap<>();
        for (Prediction p : predictions) {
            Accumulator acc = table.computeIfAbsent(
                    p.getUser().getId(),
                    k -> new Accumulator(p.getUser())
            );
            Match match = p.getMatch();
            acc.totalPredictions++;
            if (match.getStatus() != MatchStatus.COMPLETED) continue;
            acc.totalPoints += p.getPoints();
            Integer actualHome = match.getHomeScore();
            Integer actualAway = match.getAwayScore();
            if (actualHome == null || actualAway == null) continue;
            if (p.getHomeScore() == actualHome && p.getAwayScore() == actualAway) {
                acc.exactScoreHits++;
            } else if (Integer.compare(p.getHomeScore(), p.getAwayScore())
                    == Integer.compare(actualHome, actualAway)) {
                acc.winnerHits++;
            } else {
                acc.wrongs++;
            }
        }

        List<Accumulator> ordered = new ArrayList<>(table.values());
        ordered.sort(
                Comparator.comparingInt((Accumulator a) -> a.totalPoints).reversed()
                        .thenComparing(Comparator.comparingInt((Accumulator a) -> a.exactScoreHits).reversed())
                        .thenComparing(Comparator.comparingInt((Accumulator a) -> a.winnerHits).reversed())
                        .thenComparing(Comparator.comparingInt((Accumulator a) -> a.wrongs))
                        .thenComparing((a, b) -> a.user.getName().compareToIgnoreCase(b.user.getName()))
        );

        List<RankingRowResponse> rows = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            Accumulator a = ordered.get(i);
            rows.add(new RankingRowResponse(
                    i + 1,
                    a.user.getPublicId(),
                    a.user.getName(),
                    a.user.getAvatarUrl(),
                    a.totalPoints,
                    a.exactScoreHits,
                    a.winnerHits,
                    a.wrongs,
                    a.totalPredictions
            ));
        }
        return rows;
    }

    static class Accumulator {
        final User user;
        int totalPoints;
        int exactScoreHits;
        int winnerHits;
        int wrongs;
        int totalPredictions;

        Accumulator(User user) {
            this.user = user;
        }
    }
}
