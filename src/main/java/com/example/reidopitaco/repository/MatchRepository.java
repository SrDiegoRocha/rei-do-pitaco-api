package com.example.reidopitaco.repository;

import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.enums.MatchStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MatchRepository extends JpaRepository<Match, Long> {

    Optional<Match> findByPublicIdAndPhasePublicId(UUID matchPublicId, UUID phasePublicId);

    Optional<Match> findByPublicId(UUID publicId);

    @Query("""
            SELECT m FROM Match m
            JOIN FETCH m.phase p
            JOIN FETCH p.tournament
            WHERE m.publicId = :publicId
            """)
    Optional<Match> findByPublicIdWithLocation(@Param("publicId") UUID publicId);

    /**
     * Partidas agendadas cujo início cai na janela (lower, upper] e que ainda não
     * receberam o aviso da faixa indicada. Traz times/fase/torneio para montar a
     * mensagem e identificar a audiência sem N+1.
     */
    @Query("""
            SELECT m FROM Match m
            JOIN FETCH m.homeTeam
            JOIN FETCH m.awayTeam
            JOIN FETCH m.phase p
            JOIN FETCH p.tournament
            WHERE m.status = com.example.reidopitaco.enums.MatchStatus.SCHEDULED
              AND m.scheduledAt IS NOT NULL
              AND m.scheduledAt > :lower
              AND m.scheduledAt <= :upper
              AND m.notified24h = false
            """)
    List<Match> findDueForReminder24h(@Param("lower") Instant lower, @Param("upper") Instant upper);

    @Query("""
            SELECT m FROM Match m
            JOIN FETCH m.homeTeam
            JOIN FETCH m.awayTeam
            JOIN FETCH m.phase p
            JOIN FETCH p.tournament
            WHERE m.status = com.example.reidopitaco.enums.MatchStatus.SCHEDULED
              AND m.scheduledAt IS NOT NULL
              AND m.scheduledAt > :lower
              AND m.scheduledAt <= :upper
              AND m.notified4h = false
            """)
    List<Match> findDueForReminder4h(@Param("lower") Instant lower, @Param("upper") Instant upper);

    @Query("""
            SELECT m FROM Match m
            JOIN FETCH m.homeTeam
            JOIN FETCH m.awayTeam
            JOIN FETCH m.phase p
            JOIN FETCH p.tournament
            WHERE m.status = com.example.reidopitaco.enums.MatchStatus.SCHEDULED
              AND m.scheduledAt IS NOT NULL
              AND m.scheduledAt > :lower
              AND m.scheduledAt <= :upper
              AND m.notified1h = false
            """)
    List<Match> findDueForReminder1h(@Param("lower") Instant lower, @Param("upper") Instant upper);

    @Query("""
            SELECT m FROM Match m
            JOIN FETCH m.homeTeam
            JOIN FETCH m.awayTeam
            LEFT JOIN FETCH m.group
            WHERE m.phase.publicId = :phasePublicId
            """)
    List<Match> findAllByPhasePublicId(@Param("phasePublicId") UUID phasePublicId);

    @Query("""
            SELECT m FROM Match m
            JOIN m.phase p
            JOIN FETCH m.homeTeam
            JOIN FETCH m.awayTeam
            LEFT JOIN FETCH m.group
            WHERE p.tournament.publicId = :tournamentPublicId
            ORDER BY p.position ASC,
                     COALESCE(m.scheduledAt, m.createdAt) ASC,
                     m.createdAt ASC
            """)
    List<Match> findAllByTournamentPublicIdOrdered(@Param("tournamentPublicId") UUID tournamentPublicId);

    /**
     * Feed pessoal de partidas ({@code GET /api/users/me/matches}): partidas
     * <b>com horário marcado</b> dos torneios ativos onde o usuário é membro ACTIVE
     * (inclui os torneios em que ele é dono, pois o dono é membro auto-ativo).
     * Partidas sem {@code scheduledAt} ficam de fora — não entram numa timeline por data.
     *
     * <p>Janela de data por {@code scheduledAt}, semiaberta {@code [from, to)}. Os bounds
     * são sempre não-nulos: o service passa sentinelas (mín/máx) quando o cliente omite um
     * lado — assim o parâmetro é sempre comparado contra a coluna, evitando o
     * "could not determine data type of parameter" do Postgres com {@code :param IS NULL}.
     * O {@code Pageable} aplica o teto de itens (sem ordenação própria — a ordem cronológica
     * vem do {@code ORDER BY}); todos os fetch joins são to-one, então o limite é aplicado
     * no banco sem cair em memória. Faz fetch de fase/torneio/times/grupo para o mapper
     * montar o card sem N+1.
     */
    @Query("""
            SELECT m FROM Match m
            JOIN FETCH m.phase p
            JOIN FETCH p.tournament t
            JOIN FETCH t.settings
            JOIN FETCH m.homeTeam
            JOIN FETCH m.awayTeam
            LEFT JOIN FETCH m.group
            WHERE m.scheduledAt IS NOT NULL
              AND m.scheduledAt >= :from
              AND m.scheduledAt < :to
              AND t.active = true
              AND t.id IN (
                  SELECT tm.tournament.id FROM TournamentMember tm
                  WHERE tm.user.publicId = :userPublicId
                    AND tm.status = com.example.reidopitaco.enums.TournamentMemberStatus.ACTIVE
              )
            ORDER BY m.scheduledAt ASC, m.createdAt ASC
            """)
    List<Match> findScheduledForUserFeed(
            @Param("userPublicId") UUID userPublicId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable
    );

    /**
     * Badge "X jogos esperando seu pitaco": conta partidas {@code SCHEDULED} com janela
     * de palpite ainda aberta ({@code now < scheduledAt}), de torneios {@code IN_PROGRESS}
     * ativos onde o usuário é membro ACTIVE e nas quais ele <b>ainda não palpitou</b>.
     */
    @Query("""
            SELECT COUNT(m) FROM Match m
            JOIN m.phase p
            JOIN p.tournament t
            WHERE m.status = com.example.reidopitaco.enums.MatchStatus.SCHEDULED
              AND m.scheduledAt IS NOT NULL
              AND m.scheduledAt > :now
              AND t.active = true
              AND t.status = com.example.reidopitaco.enums.TournamentStatus.IN_PROGRESS
              AND t.id IN (
                  SELECT tm.tournament.id FROM TournamentMember tm
                  WHERE tm.user.publicId = :userPublicId
                    AND tm.status = com.example.reidopitaco.enums.TournamentMemberStatus.ACTIVE
              )
              AND NOT EXISTS (
                  SELECT 1 FROM Prediction pr
                  WHERE pr.match = m
                    AND pr.user.publicId = :userPublicId
              )
            """)
    long countPendingPredictionsForUser(
            @Param("userPublicId") UUID userPublicId,
            @Param("now") Instant now
    );

    @Query("""
            SELECT m FROM Match m
            JOIN FETCH m.homeTeam
            JOIN FETCH m.awayTeam
            LEFT JOIN FETCH m.group
            WHERE m.phase.publicId = :phasePublicId
              AND m.round = :round
            """)
    List<Match> findAllByPhasePublicIdAndRound(@Param("phasePublicId") UUID phasePublicId, @Param("round") int round);

    @Query("""
            SELECT m FROM Match m
            JOIN FETCH m.homeTeam
            JOIN FETCH m.awayTeam
            JOIN FETCH m.group g
            WHERE m.phase.publicId = :phasePublicId
              AND g.publicId = :groupPublicId
            """)
    List<Match> findAllByPhasePublicIdAndGroupPublicId(
            @Param("phasePublicId") UUID phasePublicId,
            @Param("groupPublicId") UUID groupPublicId
    );

    List<Match> findAllByTieId(UUID tieId);

    /**
     * Pernas de um confronto ({@code tieId}) dentro de um torneio, ordenadas por rodada
     * (ida antes da volta). Escopado pelo torneio para não vazar ties de outros torneios;
     * faz fetch de times/grupo para o mapper montar o {@code MatchResponse} sem N+1.
     */
    @Query("""
            SELECT m FROM Match m
            JOIN m.phase p
            JOIN FETCH m.homeTeam
            JOIN FETCH m.awayTeam
            LEFT JOIN FETCH m.group
            WHERE m.tieId = :tieId
              AND p.tournament.publicId = :tournamentPublicId
            ORDER BY m.round ASC, m.createdAt ASC
            """)
    List<Match> findAllByTieIdAndTournamentPublicId(
            @Param("tieId") UUID tieId,
            @Param("tournamentPublicId") UUID tournamentPublicId
    );

    long countByPhaseId(Long phaseId);

    long countByGroupId(Long groupId);

    @Query("""
            SELECT COUNT(m) FROM Match m
            WHERE m.phase.id = :phaseId
              AND (m.homeTeam.id = :teamId OR m.awayTeam.id = :teamId)
            """)
    long countByPhaseAndTeam(@Param("phaseId") Long phaseId, @Param("teamId") Long teamId);

    @Query("""
            SELECT COUNT(m) FROM Match m
            WHERE m.phase.id = :phaseId
              AND m.round = :round
              AND (m.homeTeam.id = :teamId OR m.awayTeam.id = :teamId)
              AND (:excludeMatchId IS NULL OR m.id <> :excludeMatchId)
            """)
    long countTeamMatchesInRound(
            @Param("phaseId") Long phaseId,
            @Param("round") int round,
            @Param("teamId") Long teamId,
            @Param("excludeMatchId") Long excludeMatchId
    );

    long countByPhaseIdAndStatus(Long phaseId, MatchStatus status);
}
