package com.example.reidopitaco.repository;

import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.enums.TournamentMemberStatus;
import com.example.reidopitaco.enums.TournamentPrivacy;
import com.example.reidopitaco.enums.TournamentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TournamentRepository extends JpaRepository<Tournament, Long> {

    Optional<Tournament> findByPublicIdAndActiveTrue(UUID publicId);

    Optional<Tournament> findByInviteCodeAndActiveTrue(String inviteCode);

    boolean existsByInviteCode(String inviteCode);

    Page<Tournament> findAllByOwnerPublicIdAndActiveTrue(UUID ownerPublicId, Pageable pageable);

    Page<Tournament> findAllByPrivacyAndStatusInAndActiveTrue(
            TournamentPrivacy privacy,
            Collection<TournamentStatus> statuses,
            Pageable pageable
    );

    @Query("""
            SELECT t FROM Tournament t
            JOIN TournamentMember m ON m.tournament = t
            WHERE m.user.publicId = :userPublicId
              AND m.status = :memberStatus
              AND t.active = true
            """)
    Page<Tournament> findJoinedByUser(
            @Param("userPublicId") UUID userPublicId,
            @Param("memberStatus") TournamentMemberStatus memberStatus,
            Pageable pageable
    );

    /**
     * Torneios ativos em IN_PROGRESS onde o usuário é member ACTIVE — universo do card de
     * pendências do Pick'em ({@code GET /api/users/me/pickems/pending}).
     */
    @Query("""
            SELECT t FROM Tournament t
            JOIN TournamentMember m ON m.tournament = t
            WHERE m.user.publicId = :userPublicId
              AND m.status = com.example.reidopitaco.enums.TournamentMemberStatus.ACTIVE
              AND t.active = true
              AND t.status = com.example.reidopitaco.enums.TournamentStatus.IN_PROGRESS
            """)
    List<Tournament> findInProgressWhereUserIsActiveMember(@Param("userPublicId") UUID userPublicId);
}
