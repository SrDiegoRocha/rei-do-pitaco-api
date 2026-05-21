package com.example.futbet.repository;

import com.example.futbet.entity.Tournament;
import com.example.futbet.enums.TournamentMemberStatus;
import com.example.futbet.enums.TournamentPrivacy;
import com.example.futbet.enums.TournamentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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
}
