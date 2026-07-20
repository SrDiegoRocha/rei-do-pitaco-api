package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.response.MatchResponse;
import com.example.reidopitaco.dto.response.PhasePredictionStatsResponse;
import com.example.reidopitaco.entity.PhaseGroup;
import com.example.reidopitaco.entity.PhasePrediction;
import com.example.reidopitaco.entity.PhasePredictionPosition;
import com.example.reidopitaco.entity.PhasePredictionTie;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.enums.MatchType;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.exception.PhaseNotFoundException;
import com.example.reidopitaco.mapper.MatchMapper;
import com.example.reidopitaco.repository.PhasePredictionPositionRepository;
import com.example.reidopitaco.repository.PhasePredictionRepository;
import com.example.reidopitaco.repository.PhasePredictionTieRepository;
import com.example.reidopitaco.repository.TournamentPhaseRepository;
import com.example.reidopitaco.service.PhasePredictionContextService.PhaseContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Previsão da galera" do Pick'em: distribuições agregadas dos palpites de fase (campeão/vice/3º
 * no mata-mata; 1º e classificados por grupo na tabela). Só contagens e percentuais.
 */
@Service
public class PhasePredictionStatsService {

    private final TournamentPhaseRepository phaseRepository;
    private final PhasePredictionRepository predictionRepository;
    private final PhasePredictionPositionRepository positionRepository;
    private final PhasePredictionTieRepository tieRepository;
    private final PhasePredictionContextService contextService;
    private final TournamentAccessGuard accessGuard;
    private final MatchMapper matchMapper;

    public PhasePredictionStatsService(
            TournamentPhaseRepository phaseRepository,
            PhasePredictionRepository predictionRepository,
            PhasePredictionPositionRepository positionRepository,
            PhasePredictionTieRepository tieRepository,
            PhasePredictionContextService contextService,
            TournamentAccessGuard accessGuard,
            MatchMapper matchMapper
    ) {
        this.phaseRepository = phaseRepository;
        this.predictionRepository = predictionRepository;
        this.positionRepository = positionRepository;
        this.tieRepository = tieRepository;
        this.contextService = contextService;
        this.accessGuard = accessGuard;
        this.matchMapper = matchMapper;
    }

    @Transactional(readOnly = true)
    public PhasePredictionStatsResponse stats(
            UUID requesterPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId
    ) {
        Tournament tournament = accessGuard.requireViewable(requesterPublicId, tournamentPublicId);
        TournamentPhase phase = phaseRepository
                .findByPublicIdAndTournamentPublicId(phasePublicId, tournamentPublicId)
                .orElseThrow(PhaseNotFoundException::new);

        List<PhasePrediction> predictions = predictionRepository.findAllByPhaseId(phase.getId());
        List<Long> ids = predictions.stream().map(PhasePrediction::getId).toList();

        if (phase.getPhaseType() == TournamentPhaseType.KNOCKOUT) {
            return new PhasePredictionStatsResponse(
                    phase.getPublicId(),
                    phase.getPhaseType(),
                    predictions.size(),
                    null,
                    bracketStats(tournament, phase, ids)
            );
        }
        return new PhasePredictionStatsResponse(
                phase.getPublicId(),
                phase.getPhaseType(),
                predictions.size(),
                tableStats(ids),
                null
        );
    }

    // ---------------------------------------------------------------- tabela

    private PhasePredictionStatsResponse.TableStats tableStats(List<Long> predictionIds) {
        if (predictionIds.isEmpty()) {
            return new PhasePredictionStatsResponse.TableStats(List.of());
        }
        List<PhasePredictionPosition> rows = positionRepository.findAllByPredictionIds(predictionIds);

        // Agrupa por bloco (grupo, ou null em ROUND_ROBIN), preservando a ordem dos grupos.
        Map<Long, List<PhasePredictionPosition>> byGroup = rows.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getGroup() == null ? -1L : r.getGroup().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        // Ordena os blocos pela posição do grupo (bloco único de ROUND_ROBIN primeiro/único).
        List<List<PhasePredictionPosition>> orderedBlocks = byGroup.values().stream()
                .sorted(Comparator.comparingInt(rows2 ->
                        rows2.get(0).getGroup() == null ? -1 : rows2.get(0).getGroup().getPosition()))
                .toList();

        List<PhasePredictionStatsResponse.GroupStats> groups = new ArrayList<>();
        for (List<PhasePredictionPosition> groupRows : orderedBlocks) {
            PhaseGroup group = groupRows.get(0).getGroup();
            long pickems = groupRows.stream()
                    .map(r -> r.getPhasePrediction().getId())
                    .distinct()
                    .count();

            TeamCounts firstPlaceCounts = new TeamCounts();
            TeamCounts qualifierCounts = new TeamCounts();
            for (PhasePredictionPosition row : groupRows) {
                if (row.getPredictedPosition() == 1) {
                    firstPlaceCounts.add(row.getTeam());
                }
                qualifierCounts.add(row.getTeam());
            }

            groups.add(new PhasePredictionStatsResponse.GroupStats(
                    group != null ? group.getPublicId() : null,
                    group != null ? group.getName() : null,
                    pickems,
                    sharesSumming100(firstPlaceCounts),
                    sharesIndividualPct(qualifierCounts, pickems)
            ));
        }
        return new PhasePredictionStatsResponse.TableStats(groups);
    }

    // ---------------------------------------------------------------- bracket

    private PhasePredictionStatsResponse.BracketStats bracketStats(
            Tournament tournament,
            TournamentPhase phase,
            List<Long> predictionIds
    ) {
        if (predictionIds.isEmpty()) {
            return new PhasePredictionStatsResponse.BracketStats(List.of(), List.of(), List.of());
        }
        // totalRounds vem do bracket real (mesma fonte do template) para localizar o slot da final.
        PhaseContext ctx = contextService.contextFor(tournament, phase);
        if (ctx.bracket() == null) {
            return new PhasePredictionStatsResponse.BracketStats(List.of(), List.of(), List.of());
        }
        int totalRounds = ctx.bracket().totalRounds();

        List<PhasePredictionTie> ties = tieRepository.findAllByPredictionIds(predictionIds);
        TeamCounts champion = new TeamCounts();
        TeamCounts runnerUp = new TeamCounts();
        TeamCounts thirdPlace = new TeamCounts();
        for (PhasePredictionTie tie : ties) {
            if (tie.getMatchType() == MatchType.THIRD_PLACE) {
                thirdPlace.add(tie.getPredictedWinnerTeam());
                continue;
            }
            if (tie.getRoundNumber() == totalRounds && tie.getSlotIndex() == 0) {
                champion.add(tie.getPredictedWinnerTeam());
                Team loser = tie.getPredictedWinnerTeam().getId().equals(tie.getPredictedHomeTeam().getId())
                        ? tie.getPredictedAwayTeam()
                        : tie.getPredictedHomeTeam();
                runnerUp.add(loser);
            }
        }
        return new PhasePredictionStatsResponse.BracketStats(
                sharesSumming100(champion),
                sharesSumming100(runnerUp),
                sharesSumming100(thirdPlace)
        );
    }

    // ---------------------------------------------------------------- percentuais

    /**
     * Distribuição de escolha única: percentuais inteiros que somam exatamente 100 (método do
     * maior resto), sobre o total de escolhas da própria lista. Ordenada por contagem desc.
     */
    private List<PhasePredictionStatsResponse.TeamShare> sharesSumming100(TeamCounts counts) {
        long total = counts.total();
        if (total <= 0) {
            return List.of();
        }
        List<TeamCount> ordered = counts.sorted();
        int n = ordered.size();
        int[] pct = new int[n];
        double[] remainder = new double[n];
        int assigned = 0;
        for (int i = 0; i < n; i++) {
            double exact = ordered.get(i).count() * 100.0 / total;
            pct[i] = (int) Math.floor(exact);
            remainder[i] = exact - pct[i];
            assigned += pct[i];
        }
        for (int leftover = 100 - assigned; leftover > 0; leftover--) {
            int best = 0;
            double bestRemainder = -1;
            for (int i = 0; i < n; i++) {
                if (remainder[i] > bestRemainder) {
                    bestRemainder = remainder[i];
                    best = i;
                }
            }
            pct[best]++;
            remainder[best] = -1;
        }
        List<PhasePredictionStatsResponse.TeamShare> shares = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            shares.add(toShare(ordered.get(i), pct[i]));
        }
        return shares;
    }

    /**
     * Distribuição multi-escolha (classificados): pct individual = % dos Pick'ems do bloco que
     * incluíram o time (arredondamento simples — a soma passa de 100 por design).
     */
    private List<PhasePredictionStatsResponse.TeamShare> sharesIndividualPct(
            TeamCounts counts,
            long base
    ) {
        return counts.sorted().stream()
                .map(tc -> toShare(tc, base <= 0 ? 0 : (int) Math.round(tc.count() * 100.0 / base)))
                .toList();
    }

    private PhasePredictionStatsResponse.TeamShare toShare(TeamCount teamCount, int pct) {
        MatchResponse.TeamRef ref = matchMapper.toTeamRef(teamCount.team());
        return new PhasePredictionStatsResponse.TeamShare(ref, teamCount.count(), pct);
    }

    private record TeamCount(Team team, long count) {
    }

    /** Contagem por time, keyed pelo id interno (não depende de equals/hashCode da entity). */
    private static class TeamCounts {
        private final Map<Long, Team> teams = new HashMap<>();
        private final Map<Long, Long> counts = new HashMap<>();

        void add(Team team) {
            teams.putIfAbsent(team.getId(), team);
            counts.merge(team.getId(), 1L, Long::sum);
        }

        long total() {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }

        List<TeamCount> sorted() {
            return teams.values().stream()
                    .map(t -> new TeamCount(t, counts.get(t.getId())))
                    .sorted(Comparator
                            .comparingLong(TeamCount::count).reversed()
                            .thenComparing(tc -> tc.team().getName(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }
}
