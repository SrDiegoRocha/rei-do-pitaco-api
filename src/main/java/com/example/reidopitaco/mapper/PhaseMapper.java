package com.example.reidopitaco.mapper;

import com.example.reidopitaco.dto.response.PhaseResponse;
import com.example.reidopitaco.entity.TournamentPhase;
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
                phase.getPlaysInsideGroupOnly(),
                phase.isHasThirdPlace(),
                phase.getFinalLegMode(),
                phase.getEffectiveBracketMode(),
                groupCount,
                teamCount,
                phase.getFinalizedAt(),
                phase.getCreatedAt(),
                phase.getUpdatedAt()
        );
    }
}
