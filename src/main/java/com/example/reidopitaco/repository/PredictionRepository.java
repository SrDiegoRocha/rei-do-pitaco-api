package com.example.reidopitaco.repository;

import com.example.reidopitaco.entity.Prediction;
import com.example.reidopitaco.enums.MatchType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PredictionRepository extends JpaRepository<Prediction, Long> {

    Optional<Prediction> findByMatchPublicIdAndUserPublicId(UUID matchPublicId, UUID userPublicId);

    List<Prediction> findAllByMatchId(Long matchId);

    /** Ids (internos) dos usuários que palpitaram numa partida — alvo do aviso de resultado. */
    @Query("SELECT p.user.id FROM Prediction p WHERE p.match.id = :matchId")
    List<Long> findUserIdsByMatchId(@Param("matchId") Long matchId);

    List<Prediction> findAllByMatchPublicId(UUID matchPublicId);

    List<Prediction> findAllByTournamentPublicIdAndUserPublicId(
            UUID tournamentPublicId,
            UUID userPublicId
    );

    /**
     * Palpites do torneio para o cálculo do ranking, com filtros opcionais por fase,
     * grupo, rodada e tipo de partida (cada um ignorado quando {@code null}). Faz fetch
     * de user, match e group para o ranking não cair em N+1 (cada round-trip ao Neon
     * custa caro). O {@code matchType} separa Final de Disputa de 3º, que compartilham
     * o mesmo {@code round} em mata-mata.
     */
    @Query("""
            SELECT p FROM Prediction p
            JOIN FETCH p.user
            JOIN FETCH p.match m
            LEFT JOIN FETCH m.group g
            JOIN m.phase ph
            WHERE p.tournament.publicId = :tournamentPublicId
              AND (:phaseId IS NULL OR ph.publicId = :phaseId)
              AND (:groupId IS NULL OR g.publicId = :groupId)
              AND (:round IS NULL OR m.round = :round)
              AND (:matchType IS NULL OR m.matchType = :matchType)
            """)
    List<Prediction> findForRanking(
            @Param("tournamentPublicId") UUID tournamentPublicId,
            @Param("phaseId") UUID phaseId,
            @Param("groupId") UUID groupId,
            @Param("round") Integer round,
            @Param("matchType") MatchType matchType
    );
}
