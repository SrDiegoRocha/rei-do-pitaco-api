package com.example.reidopitaco.dto.response;

import com.example.reidopitaco.dto.response.MatchResponse.TeamRef;
import com.example.reidopitaco.enums.TournamentPhaseType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model agregado da aba "Retrospecto" no detalhe da partida
 * ({@code GET /api/tournaments/{tid}/matches/{mid}/analysis}). Reúne, numa única resposta,
 * o contexto na competição, o confronto direto, a forma recente de cada time e o desempenho
 * dos palpiteiros — tudo calculado no backend a partir das partidas <b>deste torneio</b>
 * (fonte única de verdade; o front só apresenta). Nada é persistido.
 *
 * <p>{@code home}/{@code away} são o mandante/visitante <b>desta</b> partida. As janelas são
 * fixas: {@code recentWindow = 10} (últimos jogos) e {@code headToHeadWindow = 2} (confrontos
 * diretos), ecoadas no response para o front não hardcodar.
 */
public record MatchAnalysisResponse(
        UUID matchId,
        TournamentPhaseType phaseType,
        TeamFormSummary home,
        TeamFormSummary away,
        HeadToHead headToHead,
        ExpectedGoals expectedGoals,   // null se algum dos times não tem jogos na janela
        int recentWindow,
        int headToHeadWindow
) {

    /** Projeção de gols pela forma (ataque × defesa) — NÃO é xG de finalização. */
    public record ExpectedGoals(double home, double away) {
    }

    public record TeamFormSummary(
            TeamRef team,
            TeamStandingContext standing,   // null quando o contexto seria NONE
            List<TeamFormMatch> recentMatches,
            TeamFormStats stats,
            TeamPredictorStats predictors,
            Integer restDays               // dias desde o último jogo; null se indeterminado
    ) {
    }

    /**
     * Contexto posicional do time. {@code kind}:
     * {@code ROUND_ROBIN} (posição na tabela da fase atual),
     * {@code GROUP} (posição dentro do grupo na fase atual),
     * {@code PREVIOUS_PHASE} (posição final na fase anterior, para mata-mata vindo de liga/grupos).
     */
    public record TeamStandingContext(
            String kind,
            int position,
            int totalTeams,
            Integer points,       // null quando não se aplica
            Integer played,       // null quando não se aplica
            String groupName,     // preenchido só quando kind == GROUP
            UUID phaseId,
            String phaseName
    ) {
    }

    public record TeamFormMatch(
            UUID matchId,
            UUID phaseId,
            String phaseName,
            int round,
            Instant scheduledAt,
            TeamRef opponent,
            boolean playedHome,
            int goalsFor,           // placar decisivo (prorrogação se houve, senão 90')
            int goalsAgainst,
            String outcome,         // "W" | "D" | "L", pela ótica do dono e pelo placar decisivo
            boolean hadExtraTime,
            Integer penaltyFor,     // pênaltis do dono SE decidido nos pênaltis; senão null
            Integer penaltyAgainst,
            Boolean advanced        // avançou nos pênaltis? null quando não houve pênaltis
    ) {
    }

    public record TeamFormStats(
            int played,
            int wins,
            int draws,
            int losses,
            int goalsFor,
            int goalsAgainst,
            int goalDifference,
            int cleanSheets,
            int failedToScore,
            int overTwoFive,        // jogos com 3+ gols no total
            int bothTeamsScored,    // jogos em que os dois marcaram
            int points,             // aproveitamento 3/1/0 (não usa a pontuação do torneio)
            Integer performancePct, // round(points / (played*3) * 100); null se played=0
            FormStreak streak,      // null se played=0
            VenueRecord homeRecord, // null se não houve jogos em casa na janela
            VenueRecord awayRecord  // idem, como visitante
    ) {
    }

    public record TeamPredictorStats(
            int ratedMatches,
            int totalPredictions,
            Double exactScoreRate,      // 0-100; null se totalPredictions=0
            Double correctOutcomeRate,  // 0-100; null se totalPredictions=0
            Double averagePoints,       // null se totalPredictions=0
            Double upsetRate            // 0-100; null se ratedMatches=0
    ) {
    }

    /** Sequência atual: {@code WIN | LOSS | DRAW | UNBEATEN | WINLESS}. */
    public record FormStreak(String type, int count) {
    }

    public record VenueRecord(
            int played,
            int wins,
            int draws,
            int losses,
            int goalsFor,
            int goalsAgainst
    ) {
    }

    public record HeadToHead(
            int totalMeetings,      // confrontos anteriores entre os dois no torneio (0 se nunca)
            int homeTeamWins,       // orientado ao mandante DESTA partida
            int draws,
            int awayTeamWins,
            int homeTeamGoals,
            int awayTeamGoals,
            Double averageGoals,    // (homeTeamGoals + awayTeamGoals) / totalMeetings; null se 0
            List<HeadToHeadMatch> recentMeetings
    ) {
    }

    public record HeadToHeadMatch(
            UUID matchId,
            UUID phaseId,
            String phaseName,
            int round,
            Instant scheduledAt,
            TeamRef homeTeam,       // como jogaram NAQUELE confronto (mando pode estar invertido)
            TeamRef awayTeam,
            int homeGoals,          // placar decisivo daquele confronto
            int awayGoals,
            boolean hadExtraTime,
            Integer penaltyHomeGoals,
            Integer penaltyAwayGoals
    ) {
    }
}
