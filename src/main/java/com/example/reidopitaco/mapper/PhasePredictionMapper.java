package com.example.reidopitaco.mapper;

import com.example.reidopitaco.dto.response.PhasePredictionResponse;
import com.example.reidopitaco.entity.PhasePrediction;
import com.example.reidopitaco.entity.PhasePredictionPosition;
import com.example.reidopitaco.entity.PhasePredictionTie;
import com.example.reidopitaco.service.AvatarService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PhasePredictionMapper {

    private final MatchMapper matchMapper;
    private final AvatarService avatarService;

    public PhasePredictionMapper(MatchMapper matchMapper, AvatarService avatarService) {
        this.matchMapper = matchMapper;
        this.avatarService = avatarService;
    }

    /**
     * Sem breakdown de acertos ({@code outcome}/{@code terminals} nulos) — usado logo após o
     * upsert, quando ainda não há base de comparação carregada.
     */
    public PhasePredictionResponse toResponse(
            PhasePrediction prediction,
            List<PhasePredictionPosition> positions,
            List<PhasePredictionTie> ties
    ) {
        return toResponse(prediction, positions, ties, null, null, null);
    }

    /**
     * Com breakdown de acertos calculado on-demand contra os resultados reais. Os mapas de
     * outcome são indexados pela própria entidade filha; ausência de chave = sem base
     * ({@code outcome} nulo naquela linha).
     */
    public PhasePredictionResponse toResponse(
            PhasePrediction prediction,
            List<PhasePredictionPosition> positions,
            List<PhasePredictionTie> ties,
            Map<Long, PhasePredictionResponse.PositionOutcome> positionOutcomes,
            Map<Long, PhasePredictionResponse.TieOutcome> tieOutcomes,
            PhasePredictionResponse.TerminalOutcome terminals
    ) {
        List<PhasePredictionResponse.PositionRow> positionRows = positions.stream()
                .map(p -> new PhasePredictionResponse.PositionRow(
                        p.getGroup() != null ? p.getGroup().getPublicId() : null,
                        p.getGroup() != null ? p.getGroup().getName() : null,
                        matchMapper.toTeamRef(p.getTeam()),
                        p.getPredictedPosition(),
                        positionOutcomes != null ? positionOutcomes.get(p.getId()) : null
                ))
                .toList();

        List<PhasePredictionResponse.TieRow> tieRows = ties.stream()
                .map(t -> new PhasePredictionResponse.TieRow(
                        t.getRoundNumber(),
                        t.getSlotIndex(),
                        t.getMatchType(),
                        matchMapper.toTeamRef(t.getPredictedHomeTeam()),
                        matchMapper.toTeamRef(t.getPredictedAwayTeam()),
                        matchMapper.toTeamRef(t.getPredictedWinnerTeam()),
                        tieOutcomes != null ? tieOutcomes.get(t.getId()) : null
                ))
                .toList();

        return new PhasePredictionResponse(
                prediction.getPublicId(),
                prediction.getPhase().getPublicId(),
                prediction.getUser().getPublicId(),
                prediction.getUser().getName(),
                avatarService.avatarUrlFor(prediction.getUser().getName()),
                prediction.getPhaseType(),
                prediction.getPoints(),
                prediction.getPhase().getFinalizedAt() == null,
                prediction.getScoredAt(),
                positionRows,
                tieRows,
                terminals,
                prediction.getCreatedAt(),
                prediction.getUpdatedAt()
        );
    }
}
