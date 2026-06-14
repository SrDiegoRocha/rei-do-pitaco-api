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
     * Palpites do usuário para um conjunto de partidas (por id interno). Usado pelo feed
     * pessoal para anexar o palpite próprio a cada partida numa única query, evitando N+1.
     * O chamador deve garantir que {@code matchIds} não está vazio.
     */
    @Query("""
            SELECT p FROM Prediction p
            WHERE p.user.publicId = :userPublicId
              AND p.match.id IN :matchIds
            """)
    List<Prediction> findByUserPublicIdAndMatchIdIn(
            @Param("userPublicId") UUID userPublicId,
            @Param("matchIds") List<Long> matchIds
    );

    /**
     * Palpites do torneio para o cálculo do ranking, com filtros opcionais por fase,
     * grupo, rodada e tipo de partida (cada um ignorado quando {@code null}). Faz fetch
     * de user, match e group para o ranking não cair em N+1 (cada round-trip ao Neon
     * custa caro). O {@code matchType} separa Final de Disputa de 3º, que compartilham
     * o mesmo {@code round} em mata-mata.
     *
     * <p>Cada filtro opcional usa {@code cast(:param as String) is null} no guard de
     * nulidade: o {@code cast} dá contexto de tipo ao placeholder (evita o
     * "could not determine data type of parameter" do Postgres quando o param chega nulo)
     * sem mudar a semântica — a comparação real continua usando o parâmetro cru contra a
     * coluna.
     */
    @Query("""
            SELECT p FROM Prediction p
            JOIN FETCH p.user
            JOIN FETCH p.match m
            LEFT JOIN FETCH m.group g
            JOIN m.phase ph
            WHERE p.tournament.publicId = :tournamentPublicId
              AND (cast(:phaseId AS String) IS NULL OR ph.publicId = :phaseId)
              AND (cast(:groupId AS String) IS NULL OR g.publicId = :groupId)
              AND (cast(:round AS String) IS NULL OR m.round = :round)
              AND (cast(:matchType AS String) IS NULL OR m.matchType = :matchType)
            """)
    List<Prediction> findForRanking(
            @Param("tournamentPublicId") UUID tournamentPublicId,
            @Param("phaseId") UUID phaseId,
            @Param("groupId") UUID groupId,
            @Param("round") Integer round,
            @Param("matchType") MatchType matchType
    );
}
