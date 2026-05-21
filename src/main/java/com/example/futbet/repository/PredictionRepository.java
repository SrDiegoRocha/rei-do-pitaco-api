package com.example.futbet.repository;

import com.example.futbet.entity.Prediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PredictionRepository extends JpaRepository<Prediction, Long> {

    Optional<Prediction> findByMatchPublicIdAndUserPublicId(UUID matchPublicId, UUID userPublicId);

    List<Prediction> findAllByMatchId(Long matchId);

    List<Prediction> findAllByMatchPublicId(UUID matchPublicId);

    List<Prediction> findAllByTournamentPublicIdAndUserPublicId(
            UUID tournamentPublicId,
            UUID userPublicId
    );

    List<Prediction> findAllByTournamentPublicId(UUID tournamentPublicId);
}
