package com.example.reidopitaco.service;

import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.PhaseGroup;
import com.example.reidopitaco.entity.PhaseTeam;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.entity.TournamentZone;
import com.example.reidopitaco.enums.MatchStatus;
import com.example.reidopitaco.enums.MatchType;
import com.example.reidopitaco.enums.PickemState;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.enums.TournamentStatus;
import com.example.reidopitaco.repository.MatchRepository;
import com.example.reidopitaco.repository.PhaseGroupRepository;
import com.example.reidopitaco.repository.PhaseTeamRepository;
import com.example.reidopitaco.repository.TournamentZoneRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Monta o "contexto" do Pick'em de uma fase — estado da janela (aberta/travada/não-pronta),
 * instante de trava e o substrato a preencher (blocos de tabela ou primeira rodada do bracket).
 * Fonte única compartilhada pelo template ({@code GET /pickem/template}) e pela validação do
 * upsert ({@code PUT /pickem/me}), para as duas visões nunca divergirem.
 */
@Component
public class PhasePredictionContextService {

    /** Motivos de {@code NOT_READY} expostos em {@code stateReason}. */
    static final String REASON_TOURNAMENT_NOT_IN_PROGRESS = "TOURNAMENT_NOT_IN_PROGRESS";
    static final String REASON_NO_TEAMS = "NO_TEAMS";
    static final String REASON_NO_GROUPS = "NO_GROUPS";
    static final String REASON_TEAMS_NOT_ASSIGNED = "TEAMS_NOT_ASSIGNED_TO_GROUPS";
    static final String REASON_NO_QUALIFICATION_ZONES = "NO_QUALIFICATION_ZONES";
    static final String REASON_BRACKET_NOT_GENERATED = "BRACKET_NOT_GENERATED";

    private final MatchRepository matchRepository;
    private final PhaseTeamRepository phaseTeamRepository;
    private final PhaseGroupRepository groupRepository;
    private final TournamentZoneRepository zoneRepository;

    public PhasePredictionContextService(
            MatchRepository matchRepository,
            PhaseTeamRepository phaseTeamRepository,
            PhaseGroupRepository groupRepository,
            TournamentZoneRepository zoneRepository
    ) {
        this.matchRepository = matchRepository;
        this.phaseTeamRepository = phaseTeamRepository;
        this.groupRepository = groupRepository;
        this.zoneRepository = zoneRepository;
    }

    public PhaseContext contextFor(Tournament tournament, TournamentPhase phase) {
        List<Match> matches = matchRepository.findAllByPhasePublicId(phase.getPublicId());

        // Trava: a 1ª partida da fase começou (menor scheduledAt de partida não cancelada), ou o
        // 1º resultado saiu quando não há horários. Mesma janela dos palpites de partida.
        Instant lockAt = matches.stream()
                .filter(m -> m.getStatus() != MatchStatus.CANCELLED)
                .map(Match::getScheduledAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        boolean locked = (lockAt != null && !Instant.now().isBefore(lockAt))
                || matches.stream().anyMatch(m -> m.getStatus() == MatchStatus.COMPLETED);

        TableContext table = null;
        BracketContext bracket = null;
        String notReadyReason = null;

        if (phase.getPhaseType() == TournamentPhaseType.KNOCKOUT) {
            if (matches.isEmpty()) {
                notReadyReason = REASON_BRACKET_NOT_GENERATED;
            } else {
                bracket = buildBracketContext(phase, matches);
            }
        } else {
            List<PhaseTeam> phaseTeams = phaseTeamRepository.findAllByPhasePublicId(phase.getPublicId());
            notReadyReason = tableNotReadyReason(phase, phaseTeams);
            if (notReadyReason == null) {
                table = buildTableContext(phase, phaseTeams);
            }
        }

        PickemState state;
        String stateReason = null;
        if (notReadyReason != null) {
            state = PickemState.NOT_READY;
            stateReason = notReadyReason;
        } else if (locked) {
            state = PickemState.LOCKED;
        } else if (tournament.getStatus() != TournamentStatus.IN_PROGRESS) {
            state = PickemState.NOT_READY;
            stateReason = REASON_TOURNAMENT_NOT_IN_PROGRESS;
        } else {
            state = PickemState.OPEN;
        }

        return new PhaseContext(phase, state, stateReason, lockAt, table, bracket);
    }

    private String tableNotReadyReason(TournamentPhase phase, List<PhaseTeam> phaseTeams) {
        if (phase.getPhaseType() == TournamentPhaseType.GROUPS
                && groupRepository.countByPhaseId(phase.getId()) == 0) {
            return REASON_NO_GROUPS;
        }
        if (phaseTeams.isEmpty()) {
            return REASON_NO_TEAMS;
        }
        if (phase.getPhaseType() == TournamentPhaseType.GROUPS
                && phaseTeams.stream().anyMatch(pt -> pt.getGroup() == null)) {
            return REASON_TEAMS_NOT_ASSIGNED;
        }
        if (qualifyingDepthFromZones(phase) == 0) {
            return REASON_NO_QUALIFICATION_ZONES;
        }
        return null;
    }

    /**
     * Profundidade da zona de classificação: maior {@code toPosition} entre as zonas com
     * {@code nextPhase != null}. Zero quando não há zona de classificação configurada
     * (sem faixa definida, não há o que prever — D7).
     */
    private int qualifyingDepthFromZones(TournamentPhase phase) {
        List<TournamentZone> zones = zoneRepository.findAllByPhaseIdOrderByPositionAsc(phase.getId());
        return zones.stream()
                .filter(z -> z.getNextPhase() != null)
                .mapToInt(TournamentZone::getToPosition)
                .max()
                .orElse(0);
    }

    private TableContext buildTableContext(TournamentPhase phase, List<PhaseTeam> phaseTeams) {
        int globalDepth = qualifyingDepthFromZones(phase);
        List<TableBlock> blocks = new ArrayList<>();
        if (phase.getPhaseType() == TournamentPhaseType.GROUPS) {
            List<PhaseGroup> groups = groupRepository
                    .findAllByPhasePublicIdOrderByPositionAsc(phase.getPublicId());
            for (PhaseGroup group : groups) {
                List<Team> teams = phaseTeams.stream()
                        .filter(pt -> pt.getGroup() != null && pt.getGroup().getId().equals(group.getId()))
                        .map(PhaseTeam::getTeam)
                        .sorted(Comparator.comparing(Team::getName, String.CASE_INSENSITIVE_ORDER))
                        .toList();
                blocks.add(new TableBlock(group, Math.min(globalDepth, teams.size()), teams));
            }
        } else {
            List<Team> teams = phaseTeams.stream()
                    .map(PhaseTeam::getTeam)
                    .sorted(Comparator.comparing(Team::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            blocks.add(new TableBlock(null, Math.min(globalDepth, teams.size()), teams));
        }
        return new TableContext(globalDepth, blocks);
    }

    /**
     * Confrontos reais da <b>primeira rodada ordinal</b> do bracket, em ordem canônica (criação),
     * com {@code slotIndex} atribuído — mesma ordenação do {@link BracketService}. Em TWO_LEGGED,
     * as pernas de um confronto compartilham o {@code tieId} e contam como um único slot.
     */
    private BracketContext buildBracketContext(TournamentPhase phase, List<Match> matches) {
        Map<UUID, List<Match>> byTie = new LinkedHashMap<>();
        for (Match m : matches) {
            byTie.computeIfAbsent(m.getTieId(), k -> new ArrayList<>()).add(m);
        }
        // Agrupa confrontos por rodada ordinal via menor round de cada tie (cobre ida e volta).
        TreeMap<Integer, List<List<Match>>> byMinRound = new TreeMap<>();
        for (List<Match> legs : byTie.values()) {
            legs.sort(Comparator.comparingInt(Match::getRound).thenComparing(Match::getCreatedAt));
            byMinRound.computeIfAbsent(legs.get(0).getRound(), k -> new ArrayList<>()).add(legs);
        }
        List<List<Match>> firstRound = byMinRound.firstEntry().getValue().stream()
                .filter(legs -> legs.get(0).getMatchType() == MatchType.REGULAR)
                .sorted(Comparator.comparing(legs -> legs.get(0).getCreatedAt()))
                .toList();

        List<RealTie> firstRoundTies = new ArrayList<>(firstRound.size());
        for (int i = 0; i < firstRound.size(); i++) {
            List<Match> legs = firstRound.get(i);
            firstRoundTies.add(new RealTie(
                    i,
                    legs.get(0).getHomeTeam(),
                    legs.get(0).getAwayTeam(),
                    legs
            ));
        }
        return new BracketContext(phase.isHasThirdPlace(), totalRoundsFor(firstRoundTies.size()), firstRoundTies);
    }

    /**
     * Total de rodadas da árvore a partir do nº de confrontos da 1ª rodada ({@code K} confrontos
     * → {@code log2(K) + 1} rodadas). Para {@code K} fora de potência de 2 (só possível em fase
     * MANUAL), arredonda para cima — o molde continua utilizável.
     */
    static int totalRoundsFor(int firstRoundTieCount) {
        if (firstRoundTieCount <= 1) {
            return 1;
        }
        int ceilLog2 = 32 - Integer.numberOfLeadingZeros(firstRoundTieCount - 1);
        return ceilLog2 + 1;
    }

    /** Nº esperado de slots REGULAR na rodada ordinal {@code round} (1-based). */
    static int slotsForRound(int firstRoundTieCount, int round, int totalRounds) {
        if (round <= 0 || round > totalRounds) {
            return 0;
        }
        int slots = firstRoundTieCount >> (round - 1);
        return Math.max(slots, 1);
    }

    public record PhaseContext(
            TournamentPhase phase,
            PickemState state,
            String stateReason,
            Instant lockAt,
            TableContext table,
            BracketContext bracket
    ) {
    }

    public record TableContext(int qualifyingDepth, List<TableBlock> blocks) {
    }

    public record TableBlock(PhaseGroup group, int qualifyingDepth, List<Team> teams) {
    }

    public record BracketContext(boolean hasThirdPlace, int totalRounds, List<RealTie> firstRoundTies) {
    }

    public record RealTie(int slotIndex, Team homeTeam, Team awayTeam, List<Match> legs) {
    }
}
