package com.example.reidopitaco.repository;

import com.example.reidopitaco.entity.PhasePrediction;
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
public interface PhasePredictionRepository extends JpaRepository<PhasePrediction, Long> {

    @Query("""
            SELECT pp FROM PhasePrediction pp
            JOIN FETCH pp.user
            WHERE pp.phase.publicId = :phasePublicId
              AND pp.user.publicId = :userPublicId
            """)
    Optional<PhasePrediction> findByPhasePublicIdAndUserPublicId(
            @Param("phasePublicId") UUID phasePublicId,
            @Param("userPublicId") UUID userPublicId
    );

    @Query(value = """
            SELECT pp FROM PhasePrediction pp
            JOIN FETCH pp.user u
            WHERE pp.phase.publicId = :phasePublicId
            ORDER BY pp.points DESC, LOWER(u.name) ASC
            """,
            countQuery = """
            SELECT COUNT(pp) FROM PhasePrediction pp
            WHERE pp.phase.publicId = :phasePublicId
            """)
    Page<PhasePrediction> findAllByPhasePublicId(
            @Param("phasePublicId") UUID phasePublicId,
            Pageable pageable
    );

    @Query("""
            SELECT pp FROM PhasePrediction pp
            JOIN FETCH pp.user
            WHERE pp.phase.id = :phaseId
            """)
    List<PhasePrediction> findAllByPhaseId(@Param("phaseId") Long phaseId);

    @Query("""
            SELECT pp FROM PhasePrediction pp
            JOIN FETCH pp.user
            JOIN FETCH pp.phase
            WHERE pp.tournament.publicId = :tournamentPublicId
              AND pp.user.publicId = :userPublicId
            """)
    List<PhasePrediction> findAllByTournamentPublicIdAndUserPublicId(
            @Param("tournamentPublicId") UUID tournamentPublicId,
            @Param("userPublicId") UUID userPublicId
    );

    /** Ids (internos) das fases onde o usuário já tem Pick'em — filtro do card de pendências. */
    @Query("SELECT pp.phase.id FROM PhasePrediction pp WHERE pp.user.publicId = :userPublicId")
    List<Long> findPhaseIdsByUserPublicId(@Param("userPublicId") UUID userPublicId);

    /**
     * Soma de pontos de Pick'em por usuário, para o ranking. Filtros opcionais com o mesmo
     * padrão do {@code PredictionRepository.findForRanking} ({@code cast} dá contexto de tipo ao
     * placeholder nulo no Postgres). Retorna pares {@code [User, Long]}.
     */
    @Query("""
            SELECT pp.user, SUM(pp.points) FROM PhasePrediction pp
            WHERE pp.tournament.publicId = :tournamentPublicId
              AND (cast(:phaseId AS String) IS NULL OR pp.phase.publicId = :phaseId)
              AND (cast(:memberStatus AS String) IS NULL OR EXISTS (
                  SELECT 1 FROM TournamentMember tm
                  WHERE tm.tournament.id = pp.tournament.id
                    AND tm.user.id = pp.user.id
                    AND tm.status = :memberStatus
              ))
            GROUP BY pp.user
            """)
    List<Object[]> sumPointsByUser(
            @Param("tournamentPublicId") UUID tournamentPublicId,
            @Param("phaseId") UUID phaseId,
            @Param("memberStatus") TournamentMemberStatus memberStatus
    );
}
