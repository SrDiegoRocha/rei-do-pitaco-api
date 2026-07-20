package com.example.reidopitaco.repository;

import com.example.reidopitaco.entity.PhasePredictionTie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PhasePredictionTieRepository extends JpaRepository<PhasePredictionTie, Long> {

    /** Carga em lote (com os três times) para listagens/pontuação sem N+1. */
    @Query("""
            SELECT t FROM PhasePredictionTie t
            JOIN FETCH t.predictedHomeTeam
            JOIN FETCH t.predictedAwayTeam
            JOIN FETCH t.predictedWinnerTeam
            WHERE t.phasePrediction.id IN :predictionIds
            ORDER BY t.roundNumber ASC, t.matchType ASC, t.slotIndex ASC
            """)
    List<PhasePredictionTie> findAllByPredictionIds(
            @Param("predictionIds") Collection<Long> predictionIds
    );
}
