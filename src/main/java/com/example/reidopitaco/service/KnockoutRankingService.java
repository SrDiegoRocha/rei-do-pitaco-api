package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.response.StandingsResponse;
import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.PhaseTeam;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.entity.TournamentSettings;
import com.example.reidopitaco.entity.TournamentZone;
import com.example.reidopitaco.enums.MatchStatus;
import com.example.reidopitaco.enums.MatchType;
import com.example.reidopitaco.exception.PhaseFinalizeException;
import com.example.reidopitaco.repository.MatchRepository;
import com.example.reidopitaco.repository.PhaseTeamRepository;
import com.example.reidopitaco.repository.TournamentZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Ranking de uma fase KNOCKOUT para o {@code finalize}: quem sobrevive ao mata-mata vem primeiro,
 * eliminados vêm depois (quanto mais fundo caiu, melhor a posição). Diferente da tabela de liga
 * ({@link StandingsService}), aqui o vencedor de cada confronto vem do
 * {@link TieAggregateCalculator} — prorrogação e pênaltis decidem, não os pontos de vitória/empate.
 *
 * <p>Ordem das posições:
 * <ol>
 *   <li>Times vivos (venceram seu último confronto), do estágio mais fundo para o mais raso;
 *       times que ainda não jogaram nenhum confronto contam como vivos (byes manuais).</li>
 *   <li>Times eliminados, do estágio mais fundo para o mais raso (vice antes dos perdedores das
 *       semis, que vêm antes dos das quartas...). Se houve disputa de 3º lugar decidida, o
 *       vencedor dela vem antes do perdedor.</li>
 * </ol>
 * Dentro do mesmo estágio, a ordem é a canônica do bracket (criação dos confrontos).
 *
 * <p>Os contadores das linhas (J/V/E/D/gols/pontos) são informativos e seguem o placar do tempo
 * normal, como na tabela de liga — confronto decidido nos pênaltis conta como empate nos
 * contadores, mas a <b>posição</b> reflete quem avançou de verdade.
 */
@Service
public class KnockoutRankingService {

    private final MatchRepository matchRepository;
    private final PhaseTeamRepository phaseTeamRepository;
    private final TournamentZoneRepository zoneRepository;
    private final TieAggregateCalculator tieCalculator;
    private final AssetUrlResolver assetUrlResolver;

    public KnockoutRankingService(
            MatchRepository matchRepository,
            PhaseTeamRepository phaseTeamRepository,
            TournamentZoneRepository zoneRepository,
            TieAggregateCalculator tieCalculator,
            AssetUrlResolver assetUrlResolver
    ) {
        this.matchRepository = matchRepository;
        this.phaseTeamRepository = phaseTeamRepository;
        this.zoneRepository = zoneRepository;
        this.tieCalculator = tieCalculator;
        this.assetUrlResolver = assetUrlResolver;
    }

    @Transactional(readOnly = true)
    public StandingsResponse computeFor(Tournament tournament, TournamentPhase phase) {
        List<Match> matches = matchRepository.findAllByPhasePublicId(phase.getPublicId());
        List<PhaseTeam> phaseTeams = phaseTeamRepository.findAllByPhasePublicId(phase.getPublicId());

        Map<UUID, List<Match>> byTie = new LinkedHashMap<>();
        for (Match m : matches) {
            byTie.computeIfAbsent(m.getTieId(), k -> new ArrayList<>()).add(m);
        }

        // Estágios: confrontos REGULAR agrupados pelo menor round (ida+volta = 1 estágio).
        TreeMap<Integer, List<TieOutcome>> stages = new TreeMap<>();
        TieOutcome thirdPlace = null;
        for (List<Match> legs : byTie.values()) {
            legs.sort(Comparator.comparingInt(Match::getRound).thenComparing(Match::getCreatedAt));
            TieOutcome outcome = toOutcome(legs);
            if (legs.get(0).getMatchType() == MatchType.THIRD_PLACE) {
                // Se houver mais de uma (montagem manual fora do padrão), vale a mais funda.
                if (thirdPlace == null || outcome.minRound() > thirdPlace.minRound()) {
                    thirdPlace = outcome;
                }
            } else {
                stages.computeIfAbsent(outcome.minRound(), k -> new ArrayList<>()).add(outcome);
            }
        }
        for (List<TieOutcome> stage : stages.values()) {
            stage.sort(Comparator.comparing(TieOutcome::firstCreatedAt));
        }

        // Todo confronto disputado precisa de vencedor — empate no agregado sem pênaltis
        // deixaria o avanço indefinido.
        for (List<TieOutcome> stage : stages.values()) {
            for (TieOutcome tie : stage) {
                requireDecided(tie, "Tie");
            }
        }
        if (thirdPlace != null) {
            requireDecided(thirdPlace, "Third-place tie");
        }

        // Situação de cada time: último estágio jogado, se venceu, e a ordem canônica do confronto.
        Map<Long, TeamProgress> progressByTeam = new HashMap<>();
        int ordinal = 0;
        for (List<TieOutcome> stage : stages.values()) {
            ordinal++;
            for (int i = 0; i < stage.size(); i++) {
                TieOutcome tie = stage.get(i);
                boolean homeWon = tie.winner() != null
                        && tie.winner().getId().equals(tie.homeTeam().getId());
                boolean awayWon = tie.winner() != null
                        && tie.winner().getId().equals(tie.awayTeam().getId());
                progressByTeam.put(tie.homeTeam().getId(), new TeamProgress(ordinal, homeWon, i));
                progressByTeam.put(tie.awayTeam().getId(), new TeamProgress(ordinal, awayWon, i));
            }
        }

        // Ordena: vivos primeiro (estágio mais fundo antes), depois eliminados (idem); dentro do
        // estágio, vencedor do 3º lugar antes do perdedor, senão ordem canônica.
        Long thirdPlaceWinnerId = thirdPlace != null && thirdPlace.winner() != null
                ? thirdPlace.winner().getId()
                : null;
        List<Team> teams = phaseTeams.stream().map(PhaseTeam::getTeam).toList();
        List<Team> ranked = new ArrayList<>(teams);
        ranked.sort(Comparator
                .comparing((Team t) -> {
                    TeamProgress p = progressByTeam.get(t.getId());
                    return p == null || p.wonLast() ? 0 : 1;               // vivos primeiro
                })
                .thenComparing(t -> {
                    TeamProgress p = progressByTeam.get(t.getId());
                    return p == null ? 0 : -p.lastStage();                 // estágio mais fundo primeiro
                })
                .thenComparing(t -> {
                    if (thirdPlaceWinnerId == null) return 0;
                    return thirdPlaceWinnerId.equals(t.getId()) ? 0 : 1;   // vencedor do 3º antes
                })
                .thenComparing(t -> {
                    TeamProgress p = progressByTeam.get(t.getId());
                    return p == null ? Integer.MAX_VALUE : p.tieIndex();   // ordem canônica do estágio
                })
                .thenComparing(Team::getName, String.CASE_INSENSITIVE_ORDER));

        // Contadores informativos (placar do tempo normal, como na tabela de liga).
        Map<Long, StandingsService.StandingAccumulator> stats = new HashMap<>();
        for (Team team : teams) {
            stats.put(team.getId(), new StandingsService.StandingAccumulator(team));
        }
        accumulateStats(matches, stats, tournament.getSettings());

        List<TournamentZone> zones = zoneRepository.findAllByPhaseIdOrderByPositionAsc(phase.getId());
        List<StandingsResponse.StandingRow> rows = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            rows.add(buildRow(stats.get(ranked.get(i).getId()), i + 1, zones));
        }

        return new StandingsResponse(
                phase.getPublicId(),
                List.of(new StandingsResponse.GroupStandings(null, null, rows))
        );
    }

    private TieOutcome toOutcome(List<Match> legs) {
        Match first = legs.get(0);
        boolean played = legs.stream().anyMatch(m -> m.getStatus() == MatchStatus.COMPLETED);
        return new TieOutcome(
                first.getHomeTeam(),
                first.getAwayTeam(),
                first.getRound(),
                first.getCreatedAt(),
                played,
                played ? tieCalculator.compute(legs).winner() : null
        );
    }

    /** Confronto com perna concluída precisa ter vencedor; todo cancelado (void) elimina os dois. */
    private void requireDecided(TieOutcome tie, String label) {
        if (tie.played() && tie.winner() == null) {
            throw new PhaseFinalizeException(
                    label + " '" + tie.homeTeam().getName() + " x " + tie.awayTeam().getName()
                            + "' has no winner (draw on aggregate); set penalties to resolve it before finalizing"
            );
        }
    }

    private void accumulateStats(
            List<Match> matches,
            Map<Long, StandingsService.StandingAccumulator> stats,
            TournamentSettings settings
    ) {
        for (Match match : matches) {
            if (match.getStatus() != MatchStatus.COMPLETED) {
                continue;
            }
            StandingsService.StandingAccumulator home = stats.get(match.getHomeTeam().getId());
            StandingsService.StandingAccumulator away = stats.get(match.getAwayTeam().getId());
            if (home == null || away == null) {
                continue;
            }
            int homeScore = match.getHomeScore() == null ? 0 : match.getHomeScore();
            int awayScore = match.getAwayScore() == null ? 0 : match.getAwayScore();
            home.played++;
            away.played++;
            home.goalsFor += homeScore;
            home.goalsAgainst += awayScore;
            away.goalsFor += awayScore;
            away.goalsAgainst += homeScore;
            if (homeScore > awayScore) {
                home.wins++;
                away.losses++;
            } else if (homeScore < awayScore) {
                away.wins++;
                home.losses++;
            } else {
                home.draws++;
                away.draws++;
            }
        }
        for (StandingsService.StandingAccumulator acc : stats.values()) {
            acc.points = acc.wins * settings.getWinPoints()
                    + acc.draws * settings.getDrawPoints()
                    + acc.losses * settings.getLossPoints();
        }
    }

    private StandingsResponse.StandingRow buildRow(
            StandingsService.StandingAccumulator a,
            int position,
            List<TournamentZone> zones
    ) {
        TournamentZone zone = zones.stream()
                .filter(z -> position >= z.getFromPosition() && position <= z.getToPosition())
                .findFirst()
                .orElse(null);
        UUID zoneId = zone != null ? zone.getPublicId() : null;
        String zoneName = zone != null ? zone.getName() : null;
        UUID nextPhaseId = zone != null && zone.getNextPhase() != null
                ? zone.getNextPhase().getPublicId() : null;
        String nextPhaseName = zone != null && zone.getNextPhase() != null
                ? zone.getNextPhase().getName() : null;

        return new StandingsResponse.StandingRow(
                position,
                a.team.getPublicId(),
                a.team.getName(),
                a.team.getShortName(),
                assetUrlResolver.resolve(a.team.getBadgeUrl()),
                a.team.getTeamType(),
                a.team.getCountryCode(),
                a.played, a.wins, a.draws, a.losses,
                a.goalsFor, a.goalsAgainst,
                a.goalsFor - a.goalsAgainst,
                a.points,
                zoneId, zoneName, nextPhaseId, nextPhaseName,
                nextPhaseId != null
        );
    }

    private record TieOutcome(
            Team homeTeam,
            Team awayTeam,
            int minRound,
            Instant firstCreatedAt,
            boolean played,
            Team winner
    ) {
    }

    /** Último estágio jogado pelo time, se venceu o confronto e a posição canônica dele no estágio. */
    private record TeamProgress(int lastStage, boolean wonLast, int tieIndex) {
    }
}
