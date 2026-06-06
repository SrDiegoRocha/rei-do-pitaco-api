package com.example.reidopitaco.repository;

import com.example.reidopitaco.entity.TournamentMember;
import com.example.reidopitaco.enums.TournamentMemberStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TournamentMemberRepository extends JpaRepository<TournamentMember, Long> {

    Optional<TournamentMember> findByTournamentPublicIdAndUserPublicId(
            UUID tournamentPublicId,
            UUID userPublicId
    );

    Page<TournamentMember> findAllByTournamentPublicId(
            UUID tournamentPublicId,
            Pageable pageable
    );

    long countByTournamentIdAndStatus(Long tournamentId, TournamentMemberStatus status);

    /** Ids (internos) dos usuários membros ATIVOS de um torneio. */
    @Query("""
            SELECT tm.user.id FROM TournamentMember tm
            WHERE tm.tournament.id = :tournamentId
              AND tm.status = com.example.reidopitaco.enums.TournamentMemberStatus.ACTIVE
            """)
    List<Long> findActiveMemberUserIds(@Param("tournamentId") Long tournamentId);

    /** Membros ATIVOS de um torneio que ainda NÃO palpitaram numa dada partida. */
    @Query("""
            SELECT tm.user.id FROM TournamentMember tm
            WHERE tm.tournament.id = :tournamentId
              AND tm.status = com.example.reidopitaco.enums.TournamentMemberStatus.ACTIVE
              AND tm.user.id NOT IN (
                  SELECT p.user.id FROM Prediction p WHERE p.match.id = :matchId
              )
            """)
    List<Long> findActiveMembersWithoutPrediction(
            @Param("tournamentId") Long tournamentId,
            @Param("matchId") Long matchId
    );
}
