package com.example.reidopitaco.repository;

import com.example.reidopitaco.entity.PhaseTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PhaseTeamRepository extends JpaRepository<PhaseTeam, Long> {

    Optional<PhaseTeam> findByPhasePublicIdAndTeamPublicId(UUID phasePublicId, UUID teamPublicId);

    @Query("""
            SELECT pt FROM PhaseTeam pt
            JOIN FETCH pt.team
            LEFT JOIN FETCH pt.group
            WHERE pt.phase.publicId = :phasePublicId
            """)
    List<PhaseTeam> findAllByPhasePublicId(@Param("phasePublicId") UUID phasePublicId);

    List<PhaseTeam> findAllByPhaseIdAndGroupIsNull(Long phaseId);

    long countByPhaseId(Long phaseId);

    boolean existsByPhasePublicIdAndTeamPublicId(UUID phasePublicId, UUID teamPublicId);
}
