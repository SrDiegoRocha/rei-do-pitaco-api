package com.example.futbet.mapper;

import com.example.futbet.dto.response.ZoneResponse;
import com.example.futbet.entity.TournamentPhase;
import com.example.futbet.entity.TournamentZone;
import org.springframework.stereotype.Component;

@Component
public class ZoneMapper {

    public ZoneResponse toResponse(TournamentZone zone) {
        TournamentPhase next = zone.getNextPhase();
        return new ZoneResponse(
                zone.getPublicId(),
                zone.getName(),
                zone.getFromPosition(),
                zone.getToPosition(),
                zone.getSelectionMode(),
                zone.getBestRankedCount(),
                next != null ? next.getPublicId() : null,
                next != null ? next.getName() : null,
                zone.getPosition(),
                zone.getCreatedAt(),
                zone.getUpdatedAt()
        );
    }
}
