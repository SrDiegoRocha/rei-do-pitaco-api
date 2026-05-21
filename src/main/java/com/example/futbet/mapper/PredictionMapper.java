package com.example.futbet.mapper;

import com.example.futbet.dto.response.PredictionResponse;
import com.example.futbet.entity.Prediction;
import org.springframework.stereotype.Component;

@Component
public class PredictionMapper {

    public PredictionResponse toResponse(Prediction prediction) {
        return new PredictionResponse(
                prediction.getPublicId(),
                prediction.getMatch().getPublicId(),
                prediction.getUser().getPublicId(),
                prediction.getUser().getName(),
                prediction.getHomeScore(),
                prediction.getAwayScore(),
                prediction.getPoints(),
                prediction.getCreatedAt(),
                prediction.getUpdatedAt()
        );
    }
}
