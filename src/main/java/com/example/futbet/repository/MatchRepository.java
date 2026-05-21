package com.example.futbet.repository;

import com.example.futbet.entity.Match;
import com.example.futbet.enums.MatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MatchRepository extends JpaRepository<Match, Long> {

    Optional<Match> findByPublicIdAndPhasePublicId(UUID matchPublicId, UUID phasePublicId);

    Optional<Match> findByPublicId(UUID publicId);

    List<Match> findAllByPhasePublicId(UUID phasePublicId);

    List<Match> findAllByPhasePublicIdAndRound(UUID phasePublicId, int round);

    List<Match> findAllByPhasePublicIdAndGroupPublicId(UUID phasePublicId, UUID groupPublicId);

    List<Match> findAllByTieId(UUID tieId);

    long countByPhaseId(Long phaseId);

    long countByGroupId(Long groupId);

    @Query("""
            SELECT COUNT(m) FROM Match m
            WHERE m.phase.id = :phaseId
              AND (m.homeTeam.id = :teamId OR m.awayTeam.id = :teamId)
            """)
    long countByPhaseAndTeam(@Param("phaseId") Long phaseId, @Param("teamId") Long teamId);

    @Query("""
            SELECT COUNT(m) FROM Match m
            WHERE m.phase.id = :phaseId
              AND m.round = :round
              AND (m.homeTeam.id = :teamId OR m.awayTeam.id = :teamId)
              AND (:excludeMatchId IS NULL OR m.id <> :excludeMatchId)
            """)
    long countTeamMatchesInRound(
            @Param("phaseId") Long phaseId,
            @Param("round") int round,
            @Param("teamId") Long teamId,
            @Param("excludeMatchId") Long excludeMatchId
    );

    long countByPhaseIdAndStatus(Long phaseId, MatchStatus status);
}
