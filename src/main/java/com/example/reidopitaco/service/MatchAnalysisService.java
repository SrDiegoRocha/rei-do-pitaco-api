package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.response.MatchAnalysisResponse;
import com.example.reidopitaco.dto.response.MatchAnalysisResponse.ExpectedGoals;
import com.example.reidopitaco.dto.response.MatchAnalysisResponse.FormStreak;
import com.example.reidopitaco.dto.response.MatchAnalysisResponse.HeadToHead;
import com.example.reidopitaco.dto.response.MatchAnalysisResponse.HeadToHeadMatch;
import com.example.reidopitaco.dto.response.MatchAnalysisResponse.TeamFormMatch;
import com.example.reidopitaco.dto.response.MatchAnalysisResponse.TeamFormStats;
import com.example.reidopitaco.dto.response.MatchAnalysisResponse.TeamFormSummary;
import com.example.reidopitaco.dto.response.MatchAnalysisResponse.TeamPredictorStats;
import com.example.reidopitaco.dto.response.MatchAnalysisResponse.TeamStandingContext;
import com.example.reidopitaco.dto.response.MatchAnalysisResponse.VenueRecord;
import com.example.reidopitaco.dto.response.StandingsResponse;
import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.Prediction;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.enums.MatchStatus;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.exception.MatchNotFoundException;
import com.example.reidopitaco.mapper.MatchMapper;
import com.example.reidopitaco.repository.MatchRepository;
import com.example.reidopitaco.repository.PredictionRepository;
import com.example.reidopitaco.repository.TournamentPhaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Monta o read model agregado da aba "Retrospecto" ({@link MatchAnalysisResponse}) — ver DETAILS.md.
 * Tudo é calculado on-demand a partir das partidas <b>deste torneio</b>; nada é persistido. Reaproveita
 * {@link StandingsService#computeFor} para o contexto posicional (mesma fonte de verdade das tabelas) e
 * {@link MatchMapper#toTeamRef} para os times.
 */
@Service
public class MatchAnalysisService {

    /** Janela de "últimos jogos" (forma). Fixa — ecoada no response. */
    private static final int RECENT_WINDOW = 10;
    /** Janela de confrontos diretos. Fixa — ecoada no response. */
    private static final int HEAD_TO_HEAD_WINDOW = 2;

    private final TournamentAccessGuard accessGuard;
    private final MatchRepository matchRepository;
    private final PredictionRepository predictionRepository;
    private final TournamentPhaseRepository phaseRepository;
    private final StandingsService standingsService;
    private final MatchMapper matchMapper;

    public MatchAnalysisService(
            TournamentAccessGuard accessGuard,
            MatchRepository matchRepository,
            PredictionRepository predictionRepository,
            TournamentPhaseRepository phaseRepository,
            StandingsService standingsService,
            MatchMapper matchMapper
    ) {
        this.accessGuard = accessGuard;
        this.matchRepository = matchRepository;
        this.predictionRepository = predictionRepository;
        this.phaseRepository = phaseRepository;
        this.standingsService = standingsService;
        this.matchMapper = matchMapper;
    }

    @Transactional(readOnly = true)
    public MatchAnalysisResponse analyze(UUID requesterPublicId, UUID tournamentPublicId, UUID matchPublicId) {
        Tournament tournament = accessGuard.requireViewable(requesterPublicId, tournamentPublicId);

        Match match = matchRepository.findByPublicId(matchPublicId)
                .orElseThrow(MatchNotFoundException::new);
        if (!match.getPhase().getTournament().getId().equals(tournament.getId())) {
            throw new MatchNotFoundException();
        }

        TournamentPhase currentPhase = match.getPhase();
        Team homeTeam = match.getHomeTeam();
        Team awayTeam = match.getAwayTeam();

        // Todas as partidas COMPLETED do torneio, exceto a própria — base de forma, confronto e palpiteiros.
        List<Match> completed = matchRepository.findAllByTournamentPublicIdOrdered(tournamentPublicId).stream()
                .filter(m -> m.getStatus() == MatchStatus.COMPLETED)
                .filter(m -> !m.getId().equals(match.getId()))
                .toList();

        // Palpites do torneio inteiro (todos os status de membro), agrupados por partida — para os palpiteiros.
        Map<Long, List<Prediction>> predictionsByMatch = predictionRepository
                .findForRanking(tournamentPublicId, null, null, null, null, null).stream()
                .collect(Collectors.groupingBy(p -> p.getMatch().getId()));

        Instant reference = match.getScheduledAt() != null ? match.getScheduledAt() : Instant.now();

        TeamFormSummary home = buildTeamSummary(
                tournament, currentPhase, homeTeam, completed, predictionsByMatch, reference);
        TeamFormSummary away = buildTeamSummary(
                tournament, currentPhase, awayTeam, completed, predictionsByMatch, reference);

        HeadToHead headToHead = buildHeadToHead(completed, homeTeam, awayTeam);
        ExpectedGoals expectedGoals = buildExpectedGoals(home.stats(), away.stats());

        return new MatchAnalysisResponse(
                match.getPublicId(),
                currentPhase.getPhaseType(),
                home,
                away,
                headToHead,
                expectedGoals,
                RECENT_WINDOW,
                HEAD_TO_HEAD_WINDOW
        );
    }

    // ------------------------------------------------------------------ forma / resumo por time

    private TeamFormSummary buildTeamSummary(
            Tournament tournament,
            TournamentPhase currentPhase,
            Team team,
            List<Match> completed,
            Map<Long, List<Prediction>> predictionsByMatch,
            Instant reference
    ) {
        List<Match> teamMatches = completed.stream()
                .filter(m -> involves(m, team))
                .sorted(byRecencyDesc())
                .toList();

        List<Match> window = teamMatches.stream().limit(RECENT_WINDOW).toList();

        List<TeamFormMatch> recentMatches = window.stream()
                .map(m -> toFormMatch(m, team))
                .toList();

        TeamFormStats stats = buildStats(window, team);
        TeamStandingContext standing = buildStanding(tournament, currentPhase, team);
        TeamPredictorStats predictors = buildPredictors(teamMatches, team, predictionsByMatch);
        Integer restDays = computeRestDays(window, reference);

        return new TeamFormSummary(
                matchMapper.toTeamRef(team),
                standing,
                recentMatches,
                stats,
                predictors,
                restDays
        );
    }

    private TeamFormMatch toFormMatch(Match m, Team team) {
        boolean playedHome = m.getHomeTeam().getId().equals(team.getId());
        int decHome = decisiveHome(m);
        int decAway = decisiveAway(m);
        int goalsFor = playedHome ? decHome : decAway;
        int goalsAgainst = playedHome ? decAway : decHome;

        boolean hasPenalties = m.getHomePenalties() != null && m.getAwayPenalties() != null;
        Integer penaltyFor = null;
        Integer penaltyAgainst = null;
        Boolean advanced = null;
        if (hasPenalties) {
            penaltyFor = playedHome ? m.getHomePenalties() : m.getAwayPenalties();
            penaltyAgainst = playedHome ? m.getAwayPenalties() : m.getHomePenalties();
            advanced = penaltyFor > penaltyAgainst;
        }

        Team opponent = playedHome ? m.getAwayTeam() : m.getHomeTeam();
        TournamentPhase phase = m.getPhase();
        return new TeamFormMatch(
                m.getPublicId(),
                phase.getPublicId(),
                phase.getName(),
                m.getRound(),
                m.getScheduledAt(),
                matchMapper.toTeamRef(opponent),
                playedHome,
                goalsFor,
                goalsAgainst,
                outcome(goalsFor, goalsAgainst),
                m.getHomeExtraTimeScore() != null && m.getAwayExtraTimeScore() != null,
                penaltyFor,
                penaltyAgainst,
                advanced
        );
    }

    private TeamFormStats buildStats(List<Match> window, Team team) {
        int played = 0, wins = 0, draws = 0, losses = 0, goalsFor = 0, goalsAgainst = 0;
        int cleanSheets = 0, failedToScore = 0, overTwoFive = 0, bothTeamsScored = 0;
        VenueAccumulator homeAcc = new VenueAccumulator();
        VenueAccumulator awayAcc = new VenueAccumulator();
        List<String> outcomes = new ArrayList<>(window.size());

        for (Match m : window) {
            boolean playedHome = m.getHomeTeam().getId().equals(team.getId());
            int decHome = decisiveHome(m);
            int decAway = decisiveAway(m);
            int gf = playedHome ? decHome : decAway;
            int ga = playedHome ? decAway : decHome;
            String outcome = outcome(gf, ga);
            outcomes.add(outcome);

            played++;
            goalsFor += gf;
            goalsAgainst += ga;
            if (ga == 0) cleanSheets++;
            if (gf == 0) failedToScore++;
            if (gf + ga >= 3) overTwoFive++;
            if (gf > 0 && ga > 0) bothTeamsScored++;
            switch (outcome) {
                case "W" -> wins++;
                case "D" -> draws++;
                default -> losses++;
            }
            (playedHome ? homeAcc : awayAcc).add(outcome, gf, ga);
        }

        int points = wins * 3 + draws;
        Integer performancePct = played == 0 ? null : (int) Math.round(points * 100.0 / (played * 3));

        return new TeamFormStats(
                played, wins, draws, losses,
                goalsFor, goalsAgainst, goalsFor - goalsAgainst,
                cleanSheets, failedToScore, overTwoFive, bothTeamsScored,
                points, performancePct,
                computeStreak(outcomes),
                homeAcc.toRecord(),
                awayAcc.toRecord()
        );
    }

    /**
     * Sequência atual, a partir do jogo mais recente. Estende enquanto não houver contradição
     * (vitória e derrota no mesmo trecho); então classifica: pura → {@code WIN/LOSS/DRAW};
     * mista sem derrota → {@code UNBEATEN}; mista sem vitória → {@code WINLESS}.
     */
    private FormStreak computeStreak(List<String> outcomes) {
        if (outcomes.isEmpty()) {
            return null;
        }
        boolean hasWin = false, hasLoss = false, hasDraw = false;
        int count = 0;
        for (String o : outcomes) {
            boolean w = o.equals("W"), l = o.equals("L"), d = o.equals("D");
            if ((w && hasLoss) || (l && hasWin)) {
                break;
            }
            hasWin |= w;
            hasLoss |= l;
            hasDraw |= d;
            count++;
        }
        String type;
        if (hasWin && !hasLoss && !hasDraw) {
            type = "WIN";
        } else if (hasLoss && !hasWin && !hasDraw) {
            type = "LOSS";
        } else if (hasDraw && !hasWin && !hasLoss) {
            type = "DRAW";
        } else if (hasWin && !hasLoss) {
            type = "UNBEATEN";
        } else {
            type = "WINLESS";
        }
        return new FormStreak(type, count);
    }

    private Integer computeRestDays(List<Match> window, Instant reference) {
        Instant lastGame = null;
        for (Match m : window) { // já ordenado do mais recente ao mais antigo
            if (m.getScheduledAt() != null) {
                lastGame = m.getScheduledAt();
                break;
            }
        }
        if (lastGame == null || reference.isBefore(lastGame)) {
            return null;
        }
        return (int) Duration.between(lastGame, reference).toDays();
    }

    // ------------------------------------------------------------------ contexto posicional

    private TeamStandingContext buildStanding(Tournament tournament, TournamentPhase currentPhase, Team team) {
        return switch (currentPhase.getPhaseType()) {
            case ROUND_ROBIN -> standingFromPhase(tournament, currentPhase, team, "ROUND_ROBIN", currentPhase);
            case GROUPS -> standingFromPhase(tournament, currentPhase, team, "GROUP", currentPhase);
            case KNOCKOUT -> {
                TournamentPhase previous = previousPhaseOf(tournament, currentPhase);
                if (previous == null || previous.getPhaseType() == TournamentPhaseType.KNOCKOUT) {
                    yield null;
                }
                yield standingFromPhase(tournament, previous, team, "PREVIOUS_PHASE", previous);
            }
        };
    }

    /**
     * Localiza a linha do time na classificação da {@code tablePhase} e monta o contexto. {@code kind}
     * distingue o caso (tabela da fase atual vs. posição final na fase anterior). {@code null} se o time
     * não aparece na tabela (defensivo).
     */
    private TeamStandingContext standingFromPhase(
            Tournament tournament,
            TournamentPhase tablePhase,
            Team team,
            String kind,
            TournamentPhase referencePhase
    ) {
        StandingsResponse standings = standingsService.computeFor(tournament, tablePhase.getPublicId());
        for (StandingsResponse.GroupStandings group : standings.groups()) {
            for (StandingsResponse.StandingRow row : group.rows()) {
                if (row.teamId().equals(team.getPublicId())) {
                    return new TeamStandingContext(
                            kind,
                            row.position(),
                            group.rows().size(),
                            row.points(),
                            row.played(),
                            group.groupName(),
                            referencePhase.getPublicId(),
                            referencePhase.getName()
                    );
                }
            }
        }
        return null;
    }

    /** Fase de {@code position} imediatamente menor que a atual (a que alimenta o mata-mata), ou null. */
    private TournamentPhase previousPhaseOf(Tournament tournament, TournamentPhase currentPhase) {
        return phaseRepository
                .findAllByTournamentPublicIdOrderByPositionAsc(tournament.getPublicId()).stream()
                .filter(p -> p.getPosition() < currentPhase.getPosition())
                .max(Comparator.comparingInt(TournamentPhase::getPosition))
                .orElse(null);
    }

    // ------------------------------------------------------------------ palpiteiros

    private TeamPredictorStats buildPredictors(
            List<Match> teamMatches,
            Team team,
            Map<Long, List<Prediction>> predictionsByMatch
    ) {
        int ratedMatches = 0;
        int totalPredictions = 0;
        int exactHits = 0;
        int correctOutcomes = 0;
        long pointsSum = 0;
        int upsetMatches = 0;

        for (Match m : teamMatches) { // já são COMPLETED e envolvem o time
            List<Prediction> predictions = predictionsByMatch.get(m.getId());
            if (predictions == null || predictions.isEmpty()) {
                continue;
            }
            ratedMatches++;
            int actualHome = nz(m.getHomeScore());
            int actualAway = nz(m.getAwayScore());
            int actualHomeDec = decisiveHome(m);
            int actualAwayDec = decisiveAway(m);
            int actualOutcomeSign = Integer.compare(actualHomeDec, actualAwayDec);

            int correctInMatch = 0;
            for (Prediction p : predictions) {
                totalPredictions++;
                pointsSum += p.getPoints();
                if (p.getHomeScore() == actualHome && p.getAwayScore() == actualAway) {
                    exactHits++;
                }
                int predHomeDec = p.getHomeExtraTimeScore() != null ? p.getHomeExtraTimeScore() : p.getHomeScore();
                int predAwayDec = p.getAwayExtraTimeScore() != null ? p.getAwayExtraTimeScore() : p.getAwayScore();
                if (Integer.compare(predHomeDec, predAwayDec) == actualOutcomeSign) {
                    correctOutcomes++;
                    correctInMatch++;
                }
            }
            if (correctInMatch * 2 < predictions.size()) { // maioria errou o desfecho
                upsetMatches++;
            }
        }

        Double exactScoreRate = totalPredictions == 0 ? null : round1(100.0 * exactHits / totalPredictions);
        Double correctOutcomeRate = totalPredictions == 0 ? null : round1(100.0 * correctOutcomes / totalPredictions);
        Double averagePoints = totalPredictions == 0 ? null : round2((double) pointsSum / totalPredictions);
        Double upsetRate = ratedMatches == 0 ? null : round1(100.0 * upsetMatches / ratedMatches);

        return new TeamPredictorStats(
                ratedMatches, totalPredictions,
                exactScoreRate, correctOutcomeRate, averagePoints, upsetRate
        );
    }

    // ------------------------------------------------------------------ confronto direto

    private HeadToHead buildHeadToHead(List<Match> completed, Team home, Team away) {
        List<Match> meetings = completed.stream()
                .filter(m -> isPairing(m, home, away))
                .sorted(byRecencyDesc())
                .toList();

        int homeTeamWins = 0, draws = 0, awayTeamWins = 0, homeTeamGoals = 0, awayTeamGoals = 0;
        for (Match m : meetings) {
            boolean homeIsHere = m.getHomeTeam().getId().equals(home.getId());
            int decHome = decisiveHome(m);
            int decAway = decisiveAway(m);
            int aGoals = homeIsHere ? decHome : decAway; // gols do mandante DESTA partida
            int bGoals = homeIsHere ? decAway : decHome;
            homeTeamGoals += aGoals;
            awayTeamGoals += bGoals;
            if (aGoals > bGoals) {
                homeTeamWins++;
            } else if (bGoals > aGoals) {
                awayTeamWins++;
            } else {
                draws++;
            }
        }

        int total = meetings.size();
        Double averageGoals = total == 0 ? null : round2((double) (homeTeamGoals + awayTeamGoals) / total);

        List<HeadToHeadMatch> recent = meetings.stream()
                .limit(HEAD_TO_HEAD_WINDOW)
                .map(this::toHeadToHeadMatch)
                .toList();

        return new HeadToHead(
                total, homeTeamWins, draws, awayTeamWins, homeTeamGoals, awayTeamGoals, averageGoals, recent
        );
    }

    private HeadToHeadMatch toHeadToHeadMatch(Match m) {
        TournamentPhase phase = m.getPhase();
        return new HeadToHeadMatch(
                m.getPublicId(),
                phase.getPublicId(),
                phase.getName(),
                m.getRound(),
                m.getScheduledAt(),
                matchMapper.toTeamRef(m.getHomeTeam()),
                matchMapper.toTeamRef(m.getAwayTeam()),
                decisiveHome(m),
                decisiveAway(m),
                m.getHomeExtraTimeScore() != null && m.getAwayExtraTimeScore() != null,
                m.getHomePenalties(),
                m.getAwayPenalties()
        );
    }

    // ------------------------------------------------------------------ gols esperados

    private ExpectedGoals buildExpectedGoals(TeamFormStats home, TeamFormStats away) {
        if (home.played() == 0 || away.played() == 0) {
            return null;
        }
        double homeGf = (double) home.goalsFor() / home.played();
        double homeGa = (double) home.goalsAgainst() / home.played();
        double awayGf = (double) away.goalsFor() / away.played();
        double awayGa = (double) away.goalsAgainst() / away.played();
        double expectedHome = (homeGf + awayGa) / 2;
        double expectedAway = (awayGf + homeGa) / 2;
        return new ExpectedGoals(round1(expectedHome), round1(expectedAway));
    }

    // ------------------------------------------------------------------ helpers

    private static boolean involves(Match m, Team team) {
        return m.getHomeTeam().getId().equals(team.getId()) || m.getAwayTeam().getId().equals(team.getId());
    }

    private static boolean isPairing(Match m, Team a, Team b) {
        Long h = m.getHomeTeam().getId();
        Long w = m.getAwayTeam().getId();
        return (h.equals(a.getId()) && w.equals(b.getId())) || (h.equals(b.getId()) && w.equals(a.getId()));
    }

    /** Placar decisivo do mandante: prorrogação se lançada, senão tempo normal. */
    private static int decisiveHome(Match m) {
        return m.getHomeExtraTimeScore() != null ? m.getHomeExtraTimeScore() : nz(m.getHomeScore());
    }

    private static int decisiveAway(Match m) {
        return m.getAwayExtraTimeScore() != null ? m.getAwayExtraTimeScore() : nz(m.getAwayScore());
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static String outcome(int goalsFor, int goalsAgainst) {
        if (goalsFor > goalsAgainst) return "W";
        if (goalsFor < goalsAgainst) return "L";
        return "D";
    }

    private static Comparator<Match> byRecencyDesc() {
        return Comparator
                .comparing((Match m) -> m.getScheduledAt() != null ? m.getScheduledAt() : m.getCreatedAt())
                .thenComparing(Match::getCreatedAt)
                .thenComparingInt(Match::getRound)
                .reversed();
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Acumulador de recorte por mando; vira {@code null} quando não houve jogos naquele mando. */
    private static final class VenueAccumulator {
        int played, wins, draws, losses, goalsFor, goalsAgainst;

        void add(String outcome, int gf, int ga) {
            played++;
            goalsFor += gf;
            goalsAgainst += ga;
            switch (outcome) {
                case "W" -> wins++;
                case "D" -> draws++;
                default -> losses++;
            }
        }

        VenueRecord toRecord() {
            if (played == 0) {
                return null;
            }
            return new VenueRecord(played, wins, draws, losses, goalsFor, goalsAgainst);
        }
    }
}
