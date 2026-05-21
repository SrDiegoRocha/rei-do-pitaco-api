package com.example.futbet.repository;

import com.example.futbet.entity.Team;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TeamRepository extends JpaRepository<Team, Long> {

    Page<Team> findAllByOwnerPublicIdAndActiveTrue(UUID ownerPublicId, Pageable pageable);

    Optional<Team> findByPublicIdAndOwnerPublicIdAndActiveTrue(UUID publicId, UUID ownerPublicId);

    boolean existsByOwnerPublicIdAndNameIgnoreCaseAndActiveTrue(UUID ownerPublicId, String name);
}
