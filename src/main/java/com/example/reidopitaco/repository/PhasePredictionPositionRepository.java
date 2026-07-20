package com.example.reidopitaco.repository;

import com.example.reidopitaco.entity.PhasePredictionPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PhasePredictionPositionRepository extends JpaRepository<PhasePredictionPosition, Long> {

    /** Carga em lote (com time e grupo) para listagens/pontuação sem N+1. */
    @Query("""
            SELECT p FROM PhasePredictionPosition p
            JOIN FETCH p.team
            LEFT JOIN FETCH p.group
            WHERE p.phasePrediction.id IN :predictionIds
            ORDER BY p.predictedPosition ASC
            """)
    List<PhasePredictionPosition> findAllByPredictionIds(
            @Param("predictionIds") Collection<Long> predictionIds
    );
}
