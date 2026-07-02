package com.example.reidopitaco.mapper;

import com.example.reidopitaco.dto.response.PredictionResponse;
import com.example.reidopitaco.entity.Prediction;
import org.springframework.stereotype.Component;

@Component
public class PredictionMapper {

    public PredictionResponse toResponse(Prediction prediction) {
        return toResponse(prediction, true);
    }

    public PredictionResponse toResponse(Prediction prediction, boolean revealScores) {
        return new PredictionResponse(
                prediction.getPublicId(),
                prediction.getMatch().getPublicId(),
                prediction.getUser().getPublicId(),
                prediction.getUser().getName(),
                revealScores ? prediction.getHomeScore() : null,
                revealScores ? prediction.getAwayScore() : null,
                revealScores ? prediction.getHomeExtraTimeScore() : null,
                revealScores ? prediction.getAwayExtraTimeScore() : null,
                revealScores ? prediction.getPenaltyWinner() : null,
                revealScores ? prediction.getPoints() : null,
                prediction.getCreatedAt(),
                prediction.getUpdatedAt()
        );
    }
}
