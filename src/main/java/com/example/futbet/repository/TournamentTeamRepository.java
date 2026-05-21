package com.example.futbet.repository;

import com.example.futbet.entity.TournamentTeam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TournamentTeamRepository extends JpaRepository<TournamentTeam, Long> {

    Optional<TournamentTeam> findByTournamentPublicIdAndTeamPublicId(
            UUID tournamentPublicId,
            UUID teamPublicId
    );

    Page<TournamentTeam> findAllByTournamentPublicId(UUID tournamentPublicId, Pageable pageable);

    long countByTournamentId(Long tournamentId);

    boolean existsByTournamentPublicIdAndTeamPublicId(UUID tournamentPublicId, UUID teamPublicId);
}
