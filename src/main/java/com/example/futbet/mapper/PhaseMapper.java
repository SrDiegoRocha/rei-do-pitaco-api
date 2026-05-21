package com.example.futbet.mapper;

import com.example.futbet.dto.response.PhaseResponse;
import com.example.futbet.entity.TournamentPhase;
import org.springframework.stereotype.Component;

@Component
public class PhaseMapper {

    public PhaseResponse toResponse(TournamentPhase phase, long groupCount, long teamCount) {
        return new PhaseResponse(
                phase.getPublicId(),
                phase.getName(),
                phase.getPosition(),
                phase.getPhaseType(),
                phase.getMatchLegMode(),
                phase.getMatchGenerationMode(),
                phase.getQualifiersPerGroup(),
                phase.getPlaysInsideGroupOnly(),
                phase.isHasThirdPlace(),
                groupCount,
                teamCount,
                phase.getCreatedAt(),
                phase.getUpdatedAt()
        );
    }
}
