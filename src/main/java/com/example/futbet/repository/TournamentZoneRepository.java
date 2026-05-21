package com.example.futbet.repository;

import com.example.futbet.entity.TournamentZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TournamentZoneRepository extends JpaRepository<TournamentZone, Long> {

    Optional<TournamentZone> findByPublicIdAndPhasePublicId(UUID zonePublicId, UUID phasePublicId);

    List<TournamentZone> findAllByPhasePublicIdOrderByPositionAsc(UUID phasePublicId);

    List<TournamentZone> findAllByPhaseIdOrderByPositionAsc(Long phaseId);

    long countByPhaseId(Long phaseId);
}
