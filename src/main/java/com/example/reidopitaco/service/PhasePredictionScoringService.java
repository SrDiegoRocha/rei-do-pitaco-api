package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.response.PhasePredictionResponse;
import com.example.reidopitaco.dto.response.StandingsResponse;
import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.PhasePrediction;
import com.example.reidopitaco.entity.PhasePredictionPosition;
import com.example.reidopitaco.entity.PhasePredictionTie;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.entity.TournamentSettings;
import com.example.reidopitaco.entity.TournamentZone;
import com.example.reidopitaco.enums.MatchStatus;
import com.example.reidopitaco.enums.MatchType;
import com.example.reidopitaco.enums.PickemMatchupOutcome;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.repository.MatchRepository;
import com.example.reidopitaco.repository.PhasePredictionPositionRepository;
import com.example.reidopitaco.repository.PhasePredictionRepository;
import com.example.reidopitaco.repository.PhasePredictionTieRepository;
import com.example.reidopitaco.repository.TournamentZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pontuação do Pick'em de fase. Recalculada a cada resultado lançado/cancelado e no finalize da
 * fase — provisória enquanto a fase não termina (D6), definitiva depois. Reusa as fontes únicas
 * de verdade do resto do sistema: {@link StandingsService} (tabela/qualifies, incl. melhores-N) e
 * {@link TieAggregateCalculator} (agregado/vencedor de confronto de mata-mata).
 *
 * <p>Além do total materializado em {@code PhasePrediction.points}, expõe o breakdown por slot
 * ({@code outcome}/{@code terminals} — D4) calculado on-demand para as leituras.
 *
 * <p><b>Matching de confrontos no mata-mata:</b> por rodada ordinal, sem depender da posição do
 * slot — primeiro casa os pares exatos, depois maximiza os parciais (matching bipartido). Isso é
 * robusto a brackets cuja ordem de emparelhamento real não siga a árvore prevista (fases MANUAL
 * ou geradas antes do emparelhamento determinístico) e nunca prejudica o palpiteiro. Por confronto
 * o crédito é exclusivo: exato OU parcial, nunca a soma. A rodada 1 não pontua confronto (os pares
 * são dados) — só alimenta a árvore.
 */
@Service
public class PhasePredictionScoringService {

    private final PhasePredictionRepository predictionRepository;
    private final PhasePredictionPositionRepository positionRepository;
    private final PhasePredictionTieRepository tieRepository;
    private final MatchRepository matchRepository;
    private final TournamentZoneRepository zoneRepository;
    private final StandingsService standingsService;
    private final TieAggregateCalculator tieCalculator;

    public PhasePredictionScoringService(
            PhasePredictionRepository predictionRepository,
            PhasePredictionPositionRepository positionRepository,
            PhasePredictionTieRepository tieRepository,
            MatchRepository matchRepository,
            TournamentZoneRepository zoneRepository,
            StandingsService standingsService,
            TieAggregateCalculator tieCalculator
    ) {
        this.predictionRepository = predictionRepository;
        this.positionRepository = positionRepository;
        this.tieRepository = tieRepository;
        this.matchRepository = matchRepository;
        this.zoneRepository = zoneRepository;
        this.standingsService = standingsService;
        this.tieCalculator = tieCalculator;
    }

    // ---------------------------------------------------------------- recálculo (hooks)

    /**
     * Repontua todos os Pick'ems da fase contra o estado real atual. Chamado por
     * {@code MatchService.setResult}/{@code cancel}, {@code PhaseFinalizeService.finalize} e pelo
     * endpoint owner de recálculo. Retorna quantos Pick'ems foram repontuados.
     */
    @Transactional
    public int recalculateForPhase(TournamentPhase phase) {
        List<PhasePrediction> predictions = predictionRepository.findAllByPhaseId(phase.getId());
        if (predictions.isEmpty()) {
            return 0;
        }
        Tournament tournament = phase.getTournament();
        TournamentSettings settings = tournament.getSettings();
        Snapshot snapshot = snapshotFor(tournament, phase);

        List<Long> ids = predictions.stream().map(PhasePrediction::getId).toList();
        Map<Long, List<PhasePredictionPosition>> positionsById =
                positionRepository.findAllByPredictionIds(ids).stream()
                        .collect(Collectors.groupingBy(p -> p.getPhasePrediction().getId()));
        Map<Long, List<PhasePredictionTie>> tiesById =
                tieRepository.findAllByPredictionIds(ids).stream()
                        .collect(Collectors.groupingBy(t -> t.getPhasePrediction().getId()));

        Instant now = Instant.now();
        for (PhasePrediction prediction : predictions) {
            Breakdown breakdown = breakdownFor(
                    prediction,
                    positionsById.getOrDefault(prediction.getId(), List.of()),
                    tiesById.getOrDefault(prediction.getId(), List.of()),
                    snapshot,
                    settings
            );
            prediction.setPoints(breakdown.total());
            prediction.setScoredAt(now);
        }
        predictionRepository.saveAll(predictions);
        return predictions.size();
    }

    // ---------------------------------------------------------------- snapshot do estado real

    /**
     * Estado real da fase para comparação. Quando a fase ainda não tem nenhum resultado
     * ({@code hasResults = false}), nada é computado — pontos zerados e breakdown nulo.
     */
    @Transactional(readOnly = true)
    public Snapshot snapshotFor(Tournament tournament, TournamentPhase phase) {
        boolean hasResults =
                matchRepository.countByPhaseIdAndStatus(phase.getId(), MatchStatus.COMPLETED) > 0;
        if (!hasResults) {
            return new Snapshot(false, null, null);
        }
        if (phase.getPhaseType() == TournamentPhaseType.KNOCKOUT) {
            return new Snapshot(true, null, bracketSnapshot(phase));
        }
        return new Snapshot(true, tableSnapshot(tournament, phase), null);
    }

    private TableSnapshot tableSnapshot(Tournament tournament, TournamentPhase phase) {
        StandingsResponse standings = standingsService.computeFor(tournament, phase.getPublicId());
        Map<String, Map<UUID, StandingsResponse.StandingRow>> rowsByBlock = new HashMap<>();
        for (StandingsResponse.GroupStandings group : standings.groups()) {
            String key = blockKey(group.groupId());
            Map<UUID, StandingsResponse.StandingRow> byTeam = new HashMap<>();
            for (StandingsResponse.StandingRow row : group.rows()) {
                byTeam.put(row.teamId(), row);
            }
            rowsByBlock.put(key, byTeam);
        }
        List<int[]> qualifyingRanges = zoneRepository
                .findAllByPhaseIdOrderByPositionAsc(phase.getId())
                .stream()
                .filter(z -> z.getNextPhase() != null)
                .map(z -> new int[]{z.getFromPosition(), z.getToPosition()})
                .toList();
        return new TableSnapshot(rowsByBlock, qualifyingRanges);
    }

    private BracketSnapshot bracketSnapshot(TournamentPhase phase) {
        List<Match> matches = matchRepository.findAllByPhasePublicId(phase.getPublicId());

        Map<UUID, List<Match>> byTie = new LinkedHashMap<>();
        for (Match m : matches) {
            byTie.computeIfAbsent(m.getTieId(), k -> new ArrayList<>()).add(m);
        }
        TreeMap<Integer, List<RealTieResult>> regularByMinRound = new TreeMap<>();
        RealTieResult thirdPlaceTie = null;
        for (List<Match> legs : byTie.values()) {
            legs.sort(Comparator.comparingInt(Match::getRound).thenComparing(Match::getCreatedAt));
            TieAggregateCalculator.TieAggregate aggregate = tieCalculator.compute(legs);
            boolean complete = legs.stream().noneMatch(m -> m.getStatus() == MatchStatus.SCHEDULED);
            Set<Long> teamIds = new HashSet<>();
            teamIds.add(legs.get(0).getHomeTeam().getId());
            teamIds.add(legs.get(0).getAwayTeam().getId());
            RealTieResult result = new RealTieResult(teamIds, aggregate.winner(), complete);
            if (legs.get(0).getMatchType() == MatchType.THIRD_PLACE) {
                thirdPlaceTie = result;
            } else {
                regularByMinRound
                        .computeIfAbsent(legs.get(0).getRound(), k -> new ArrayList<>())
                        .add(result);
            }
        }

        // Rodadas ordinais: 1..N na ordem dos menores rounds crus.
        Map<Integer, List<RealTieResult>> regularByOrdinal = new HashMap<>();
        int ordinal = 0;
        for (List<RealTieResult> round : regularByMinRound.values()) {
            regularByOrdinal.put(++ordinal, round);
        }
        int firstRoundCount = regularByOrdinal.getOrDefault(1, List.of()).size();
        int totalRounds = PhasePredictionContextService.totalRoundsFor(firstRoundCount);

        // Campeão: o único confronto REGULAR da última rodada ordinal, quando ela é a final
        // (rodada ordinal == totalRounds) e o confronto está decidido. O vice sai do par da final.
        Team champion = null;
        List<RealTieResult> lastRound = regularByOrdinal.get(ordinal);
        if (ordinal == totalRounds && lastRound != null && lastRound.size() == 1) {
            RealTieResult finalTie = lastRound.get(0);
            if (finalTie.complete() && finalTie.winner() != null) {
                champion = finalTie.winner();
            }
        }

        return new BracketSnapshot(totalRounds, regularByOrdinal, thirdPlaceTie, champion);
    }

    // ---------------------------------------------------------------- breakdown por prediction

    /**
     * Breakdown completo de um Pick'em contra o snapshot real: total + desfecho por slot +
     * terminais (KO). Usado tanto pelo recálculo (só o total) quanto pelas leituras (D4).
     */
    public Breakdown breakdownFor(
            PhasePrediction prediction,
            List<PhasePredictionPosition> positions,
            List<PhasePredictionTie> ties,
            Snapshot snapshot,
            TournamentSettings settings
    ) {
        if (!snapshot.hasResults()) {
            return new Breakdown(0, Map.of(), Map.of(), null);
        }
        if (prediction.getPhaseType() == TournamentPhaseType.KNOCKOUT) {
            return bracketBreakdown(snapshot.bracket(), ties, settings);
        }
        return tableBreakdown(snapshot.table(), positions, settings);
    }

    // ---------------------------------------------------------------- tabela (RR/GROUPS)

    private Breakdown tableBreakdown(
            TableSnapshot snapshot,
            List<PhasePredictionPosition> rows,
            TournamentSettings settings
    ) {
        int total = 0;
        Map<Long, PhasePredictionResponse.PositionOutcome> outcomes = new HashMap<>();
        for (PhasePredictionPosition row : rows) {
            UUID groupId = row.getGroup() != null ? row.getGroup().getPublicId() : null;
            StandingsResponse.StandingRow real = snapshot.rowsByBlock()
                    .getOrDefault(blockKey(groupId), Map.of())
                    .get(row.getTeam().getPublicId());
            if (real == null) {
                outcomes.put(row.getId(),
                        new PhasePredictionResponse.PositionOutcome(false, false, false, 0));
                continue;
            }
            boolean predictedQualifier = inQualifyingRange(snapshot.qualifyingRanges(), row.getPredictedPosition());
            boolean qualifiedHit = predictedQualifier && real.qualifies();
            boolean exactPositionHit = real.position() == row.getPredictedPosition();
            boolean firstPlaceHit = row.getPredictedPosition() == 1 && real.position() == 1;
            int points = (qualifiedHit ? settings.getPickemQualifierPoints() : 0)
                    + (exactPositionHit ? settings.getPickemExactPositionPoints() : 0)
                    + (firstPlaceHit ? settings.getPickemFirstPlacePoints() : 0);
            total += points;
            outcomes.put(row.getId(), new PhasePredictionResponse.PositionOutcome(
                    qualifiedHit, exactPositionHit, firstPlaceHit, points));
        }
        return new Breakdown(total, outcomes, Map.of(), null);
    }

    private boolean inQualifyingRange(List<int[]> ranges, int position) {
        for (int[] range : ranges) {
            if (position >= range[0] && position <= range[1]) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- bracket (KNOCKOUT)

    private Breakdown bracketBreakdown(
            BracketSnapshot snapshot,
            List<PhasePredictionTie> ties,
            TournamentSettings settings
    ) {
        Map<Long, PhasePredictionResponse.TieOutcome> outcomes = new HashMap<>();
        int total = 0;

        // Confrontos por rodada (>= 2): matching exato primeiro, depois parciais maximizados.
        Map<Integer, List<PhasePredictionTie>> predictedByRound = ties.stream()
                .filter(t -> t.getMatchType() == MatchType.REGULAR)
                .collect(Collectors.groupingBy(PhasePredictionTie::getRoundNumber));

        for (Map.Entry<Integer, List<PhasePredictionTie>> entry : predictedByRound.entrySet()) {
            int round = entry.getKey();
            List<PhasePredictionTie> predicted = entry.getValue();
            List<RealTieResult> real = snapshot.regularByOrdinal().get(round);

            Map<Long, PickemMatchupOutcome> matchups = round >= 2 && real != null
                    ? matchRound(predicted, real)
                    : Map.of();

            for (PhasePredictionTie pick : predicted) {
                PickemMatchupOutcome matchup = round == 1 ? null : (real == null ? null : matchups.get(pick.getId()));
                Integer matchupPoints = null;
                if (matchup != null) {
                    matchupPoints = switch (matchup) {
                        case EXACT -> settings.getPickemKoMatchupExactPoints();
                        case PARTIAL -> settings.getPickemKoMatchupPartialPoints();
                        case MISS -> 0;
                    };
                    total += matchupPoints;
                }
                Boolean winnerAdvanced = winnerAdvanced(real, pick.getPredictedWinnerTeam());
                outcomes.put(pick.getId(), new PhasePredictionResponse.TieOutcome(
                        matchup, winnerAdvanced, matchupPoints));
            }
        }

        // Slot de 3º lugar: confronto (exato/parcial) + acerto de quem fica em 3º vai nos terminais.
        PhasePredictionTie thirdPick = ties.stream()
                .filter(t -> t.getMatchType() == MatchType.THIRD_PLACE)
                .findFirst()
                .orElse(null);
        if (thirdPick != null) {
            RealTieResult realThird = snapshot.thirdPlaceTie();
            PickemMatchupOutcome matchup = null;
            Integer matchupPoints = null;
            Boolean winnerAdvanced = null;
            if (realThird != null) {
                Set<Long> predictedPair = Set.of(
                        thirdPick.getPredictedHomeTeam().getId(),
                        thirdPick.getPredictedAwayTeam().getId());
                long overlap = predictedPair.stream().filter(realThird.teamIds()::contains).count();
                matchup = overlap == 2 ? PickemMatchupOutcome.EXACT
                        : overlap == 1 ? PickemMatchupOutcome.PARTIAL
                        : PickemMatchupOutcome.MISS;
                matchupPoints = switch (matchup) {
                    case EXACT -> settings.getPickemKoMatchupExactPoints();
                    case PARTIAL -> settings.getPickemKoMatchupPartialPoints();
                    case MISS -> 0;
                };
                total += matchupPoints;
                if (realThird.complete() && realThird.winner() != null) {
                    winnerAdvanced = realThird.winner().getId()
                            .equals(thirdPick.getPredictedWinnerTeam().getId());
                }
            }
            outcomes.put(thirdPick.getId(),
                    new PhasePredictionResponse.TieOutcome(matchup, winnerAdvanced, matchupPoints));
        }

        // Terminais: campeão / vice / 3º.
        TerminalComputation terminals = terminalOutcome(snapshot, ties, thirdPick, settings);
        total += terminals.points();

        return new Breakdown(total, Map.of(), outcomes, terminals.outcome());
    }

    /**
     * Matching de uma rodada: primeiro pares exatos (conjunto igual), depois matching bipartido
     * máximo entre os slots restantes e confrontos reais restantes que compartilham ≥1 time —
     * maximiza os parciais do palpiteiro independentemente da ordem dos slots.
     */
    private Map<Long, PickemMatchupOutcome> matchRound(
            List<PhasePredictionTie> predicted,
            List<RealTieResult> real
    ) {
        Map<Long, PickemMatchupOutcome> result = new HashMap<>();
        boolean[] realUsed = new boolean[real.size()];
        List<PhasePredictionTie> remaining = new ArrayList<>();

        for (PhasePredictionTie pick : predicted) {
            Set<Long> pair = Set.of(
                    pick.getPredictedHomeTeam().getId(),
                    pick.getPredictedAwayTeam().getId());
            int exactIndex = -1;
            for (int i = 0; i < real.size(); i++) {
                if (!realUsed[i] && real.get(i).teamIds().equals(pair)) {
                    exactIndex = i;
                    break;
                }
            }
            if (exactIndex >= 0) {
                realUsed[exactIndex] = true;
                result.put(pick.getId(), PickemMatchupOutcome.EXACT);
            } else {
                remaining.add(pick);
            }
        }

        // Kuhn (caminhos aumentantes) para maximizar os parciais entre os restantes.
        int[] realMatch = new int[real.size()];
        java.util.Arrays.fill(realMatch, -1);
        List<List<Integer>> adjacency = new ArrayList<>(remaining.size());
        for (PhasePredictionTie pick : remaining) {
            List<Integer> adj = new ArrayList<>();
            Set<Long> pair = Set.of(
                    pick.getPredictedHomeTeam().getId(),
                    pick.getPredictedAwayTeam().getId());
            for (int i = 0; i < real.size(); i++) {
                if (realUsed[i]) {
                    continue;
                }
                long overlap = pair.stream().filter(real.get(i).teamIds()::contains).count();
                if (overlap >= 1) {
                    adj.add(i);
                }
            }
            adjacency.add(adj);
        }
        int[] predictedMatch = new int[remaining.size()];
        java.util.Arrays.fill(predictedMatch, -1);
        for (int p = 0; p < remaining.size(); p++) {
            tryAugment(p, adjacency, realMatch, predictedMatch, new boolean[real.size()]);
        }
        for (int p = 0; p < remaining.size(); p++) {
            result.put(remaining.get(p).getId(),
                    predictedMatch[p] >= 0 ? PickemMatchupOutcome.PARTIAL : PickemMatchupOutcome.MISS);
        }
        return result;
    }

    private boolean tryAugment(
            int p,
            List<List<Integer>> adjacency,
            int[] realMatch,
            int[] predictedMatch,
            boolean[] visited
    ) {
        for (int r : adjacency.get(p)) {
            if (visited[r]) {
                continue;
            }
            visited[r] = true;
            if (realMatch[r] < 0 || tryAugment(realMatch[r], adjacency, realMatch, predictedMatch, visited)) {
                realMatch[r] = p;
                predictedMatch[p] = r;
                return true;
            }
        }
        return false;
    }

    /**
     * O time que o usuário mandou avançar de fato avançou desta rodada? {@code null} quando a
     * rodada real não existe ou o confronto real do time ainda não tem vencedor; {@code false}
     * quando o time nem chegou a essa rodada.
     */
    private Boolean winnerAdvanced(List<RealTieResult> realRound, Team predictedWinner) {
        if (realRound == null) {
            return null;
        }
        for (RealTieResult tie : realRound) {
            if (tie.teamIds().contains(predictedWinner.getId())) {
                if (!tie.complete() || tie.winner() == null) {
                    return null;
                }
                return tie.winner().getId().equals(predictedWinner.getId());
            }
        }
        return false;
    }

    private TerminalComputation terminalOutcome(
            BracketSnapshot snapshot,
            List<PhasePredictionTie> ties,
            PhasePredictionTie thirdPick,
            TournamentSettings settings
    ) {
        PhasePredictionTie finalPick = ties.stream()
                .filter(t -> t.getMatchType() == MatchType.REGULAR
                        && t.getRoundNumber() == snapshot.totalRounds()
                        && t.getSlotIndex() == 0)
                .findFirst()
                .orElse(null);

        // Campeão/vice reais: o confronto único da rodada final decidida. Comparações por id —
        // não precisamos da entity do vice, só de saber quem ele é.
        Team realChampion = snapshot.champion();
        Long realRunnerUpId = null;
        if (realChampion != null) {
            List<RealTieResult> finalRound = snapshot.regularByOrdinal().get(snapshot.totalRounds());
            RealTieResult finalTie = finalRound.get(0);
            realRunnerUpId = finalTie.teamIds().stream()
                    .filter(id -> !id.equals(realChampion.getId()))
                    .findFirst()
                    .orElse(null);
        }

        Boolean championHit = null;
        Boolean runnerUpHit = null;
        Boolean thirdPlaceHit = null;
        int points = 0;

        if (finalPick != null && realChampion != null) {
            Team predictedChampion = finalPick.getPredictedWinnerTeam();
            Team predictedRunnerUp = predictedChampion.getId().equals(finalPick.getPredictedHomeTeam().getId())
                    ? finalPick.getPredictedAwayTeam()
                    : finalPick.getPredictedHomeTeam();
            championHit = predictedChampion.getId().equals(realChampion.getId());
            if (championHit) {
                points += settings.getPickemChampionPoints();
            }
            if (realRunnerUpId != null) {
                runnerUpHit = predictedRunnerUp.getId().equals(realRunnerUpId);
                if (runnerUpHit) {
                    points += settings.getPickemRunnerUpPoints();
                }
            }
        }
        if (thirdPick != null && snapshot.thirdPlaceTie() != null
                && snapshot.thirdPlaceTie().complete()
                && snapshot.thirdPlaceTie().winner() != null) {
            thirdPlaceHit = snapshot.thirdPlaceTie().winner().getId()
                    .equals(thirdPick.getPredictedWinnerTeam().getId());
            if (thirdPlaceHit) {
                points += settings.getPickemThirdPlacePoints();
            }
        }

        if (championHit == null && runnerUpHit == null && thirdPlaceHit == null) {
            return new TerminalComputation(null, 0);
        }
        return new TerminalComputation(
                new PhasePredictionResponse.TerminalOutcome(championHit, runnerUpHit, thirdPlaceHit, points),
                points
        );
    }

    /**
     * Totais por componente de um Pick'em (para o perfil do palpiteiro): derivados do mesmo
     * breakdown das leituras, então nunca divergem do total materializado.
     */
    public ComponentTotals componentTotals(
            PhasePrediction prediction,
            List<PhasePredictionPosition> positions,
            List<PhasePredictionTie> ties,
            Snapshot snapshot,
            TournamentSettings settings
    ) {
        Breakdown breakdown = breakdownFor(prediction, positions, ties, snapshot, settings);
        int qualifier = 0;
        int exactPosition = 0;
        int firstPlace = 0;
        for (PhasePredictionResponse.PositionOutcome outcome : breakdown.positionOutcomes().values()) {
            if (Boolean.TRUE.equals(outcome.qualifiedHit())) {
                qualifier += settings.getPickemQualifierPoints();
            }
            if (Boolean.TRUE.equals(outcome.exactPositionHit())) {
                exactPosition += settings.getPickemExactPositionPoints();
            }
            if (Boolean.TRUE.equals(outcome.firstPlaceHit())) {
                firstPlace += settings.getPickemFirstPlacePoints();
            }
        }
        int koExact = 0;
        int koPartial = 0;
        for (PhasePredictionResponse.TieOutcome outcome : breakdown.tieOutcomes().values()) {
            if (outcome.matchup() == PickemMatchupOutcome.EXACT) {
                koExact += settings.getPickemKoMatchupExactPoints();
            } else if (outcome.matchup() == PickemMatchupOutcome.PARTIAL) {
                koPartial += settings.getPickemKoMatchupPartialPoints();
            }
        }
        int champion = 0;
        int runnerUp = 0;
        int thirdPlace = 0;
        PhasePredictionResponse.TerminalOutcome terminals = breakdown.terminals();
        if (terminals != null) {
            if (Boolean.TRUE.equals(terminals.championHit())) {
                champion = settings.getPickemChampionPoints();
            }
            if (Boolean.TRUE.equals(terminals.runnerUpHit())) {
                runnerUp = settings.getPickemRunnerUpPoints();
            }
            if (Boolean.TRUE.equals(terminals.thirdPlaceHit())) {
                thirdPlace = settings.getPickemThirdPlacePoints();
            }
        }
        return new ComponentTotals(
                qualifier, exactPosition, firstPlace,
                koExact, koPartial, champion, runnerUp, thirdPlace,
                breakdown.total()
        );
    }

    private String blockKey(UUID groupId) {
        return groupId == null ? "-" : groupId.toString();
    }

    // ---------------------------------------------------------------- tipos

    /** Estado real da fase. {@code table} XOR {@code bracket} preenchido quando há resultados. */
    public record Snapshot(boolean hasResults, TableSnapshot table, BracketSnapshot bracket) {
    }

    public record TableSnapshot(
            Map<String, Map<UUID, StandingsResponse.StandingRow>> rowsByBlock,
            List<int[]> qualifyingRanges
    ) {
    }

    public record BracketSnapshot(
            int totalRounds,
            Map<Integer, List<RealTieResult>> regularByOrdinal,
            RealTieResult thirdPlaceTie,
            Team champion
    ) {
    }

    public record RealTieResult(Set<Long> teamIds, Team winner, boolean complete) {
    }

    /** Total + breakdown por slot (D4). Mapas keyed pelo id da entidade filha. */
    public record Breakdown(
            int total,
            Map<Long, PhasePredictionResponse.PositionOutcome> positionOutcomes,
            Map<Long, PhasePredictionResponse.TieOutcome> tieOutcomes,
            PhasePredictionResponse.TerminalOutcome terminals
    ) {
    }

    /** Pontos por componente + total (perfil do palpiteiro). */
    public record ComponentTotals(
            int qualifier,
            int exactPosition,
            int firstPlace,
            int koMatchupExact,
            int koMatchupPartial,
            int champion,
            int runnerUp,
            int thirdPlace,
            int total
    ) {
    }

    private record TerminalComputation(PhasePredictionResponse.TerminalOutcome outcome, int points) {
    }
}
