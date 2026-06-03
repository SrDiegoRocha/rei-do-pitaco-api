package com.example.reidopitaco.repository;

import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.enums.MatchStatus;
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

    @Query("""
            SELECT m FROM Match m
            JOIN FETCH m.homeTeam
            JOIN FETCH m.awayTeam
            LEFT JOIN FETCH m.group
            WHERE m.phase.publicId = :phasePublicId
            """)
    List<Match> findAllByPhasePublicId(@Param("phasePublicId") UUID phasePublicId);

    @Query("""
            SELECT m FROM Match m
            JOIN m.phase p
            JOIN FETCH m.homeTeam
            JOIN FETCH m.awayTeam
            LEFT JOIN FETCH m.group
            WHERE p.tournament.publicId = :tournamentPublicId
            ORDER BY p.position ASC,
                     COALESCE(m.scheduledAt, m.createdAt) ASC,
                     m.createdAt ASC
            """)
    List<Match> findAllByTournamentPublicIdOrdered(@Param("tournamentPublicId") UUID tournamentPublicId);

    @Query("""
            SELECT m FROM Match m
            JOIN FETCH m.homeTeam
            JOIN FETCH m.awayTeam
            LEFT JOIN FETCH m.group
            WHERE m.phase.publicId = :phasePublicId
              AND m.round = :round
            """)
    List<Match> findAllByPhasePublicIdAndRound(@Param("phasePublicId") UUID phasePublicId, @Param("round") int round);

    @Query("""
            SELECT m FROM Match m
            JOIN FETCH m.homeTeam
            JOIN FETCH m.awayTeam
            JOIN FETCH m.group g
            WHERE m.phase.publicId = :phasePublicId
              AND g.publicId = :groupPublicId
            """)
    List<Match> findAllByPhasePublicIdAndGroupPublicId(
            @Param("phasePublicId") UUID phasePublicId,
            @Param("groupPublicId") UUID groupPublicId
    );

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
