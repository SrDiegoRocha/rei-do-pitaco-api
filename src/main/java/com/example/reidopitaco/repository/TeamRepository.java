package com.example.reidopitaco.repository;

import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.enums.TeamType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TeamRepository extends JpaRepository<Team, Long> {

    Page<Team> findAllByOwnerPublicIdAndActiveTrue(UUID ownerPublicId, Pageable pageable);

    Optional<Team> findByPublicIdAndOwnerPublicIdAndActiveTrue(UUID publicId, UUID ownerPublicId);

    Optional<Team> findByPublicIdAndActiveTrue(UUID publicId);

    boolean existsByOwnerPublicIdAndNameIgnoreCaseAndActiveTrue(UUID ownerPublicId, String name);

    /**
     * Busca times ativos por escopo: os do próprio usuário ({@code includeMine}) e/ou os do sistema
     * ({@code includeSystem}), opcionalmente filtrando por {@code type} (null = qualquer tipo)
     * e por {@code leagueSlug} (null = qualquer liga; só clubes do sistema têm liga).
     */
    @Query("""
            SELECT t FROM Team t
            LEFT JOIN t.owner o
            WHERE t.active = true
              AND ( (:includeMine = true AND o.publicId = :ownerPublicId)
                    OR (:includeSystem = true AND t.system = true) )
              AND (:type IS NULL OR t.teamType = :type)
              AND (:leagueSlug IS NULL OR t.leagueSlug = :leagueSlug)
            """)
    Page<Team> search(
            @Param("includeMine") boolean includeMine,
            @Param("includeSystem") boolean includeSystem,
            @Param("ownerPublicId") UUID ownerPublicId,
            @Param("type") TeamType type,
            @Param("leagueSlug") String leagueSlug,
            Pageable pageable
    );
}
