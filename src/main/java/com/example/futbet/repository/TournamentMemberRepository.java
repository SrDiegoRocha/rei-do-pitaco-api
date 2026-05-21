package com.example.futbet.repository;

import com.example.futbet.entity.TournamentMember;
import com.example.futbet.enums.TournamentMemberStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
