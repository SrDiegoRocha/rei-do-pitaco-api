package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.request.PlacePhasePredictionRequest;
import com.example.reidopitaco.dto.response.PendingPickemResponse;
import com.example.reidopitaco.dto.response.PhasePredictionResponse;
import com.example.reidopitaco.dto.response.PhasePredictionTemplateResponse;
import com.example.reidopitaco.dto.response.PickemRecalculationResponse;
import com.example.reidopitaco.entity.PhaseGroup;
import com.example.reidopitaco.entity.PhasePrediction;
import com.example.reidopitaco.entity.PhasePredictionPosition;
import com.example.reidopitaco.entity.PhasePredictionTie;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentMember;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.entity.TournamentSettings;
import com.example.reidopitaco.entity.User;
import com.example.reidopitaco.enums.MatchType;
import com.example.reidopitaco.enums.PickemState;
import com.example.reidopitaco.enums.TournamentMemberStatus;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.exception.BusinessException;
import com.example.reidopitaco.exception.NotTournamentMemberException;
import com.example.reidopitaco.exception.NotTournamentOwnerException;
import com.example.reidopitaco.exception.PhaseGroupNotFoundException;
import com.example.reidopitaco.exception.PhaseNotFoundException;
import com.example.reidopitaco.exception.PhasePredictionLockedException;
import com.example.reidopitaco.exception.PhasePredictionNotFoundException;
import com.example.reidopitaco.exception.TournamentNotFoundException;
import com.example.reidopitaco.mapper.MatchMapper;
import com.example.reidopitaco.mapper.PhasePredictionMapper;
import com.example.reidopitaco.repository.PhasePredictionPositionRepository;
import com.example.reidopitaco.repository.PhasePredictionRepository;
import com.example.reidopitaco.repository.PhasePredictionTieRepository;
import com.example.reidopitaco.repository.TournamentMemberRepository;
import com.example.reidopitaco.repository.TournamentPhaseRepository;
import com.example.reidopitaco.repository.TournamentRepository;
import com.example.reidopitaco.repository.UserRepository;
import com.example.reidopitaco.service.PhasePredictionContextService.BracketContext;
import com.example.reidopitaco.service.PhasePredictionContextService.PhaseContext;
import com.example.reidopitaco.service.PhasePredictionContextService.RealTie;
import com.example.reidopitaco.service.PhasePredictionContextService.TableBlock;
import com.example.reidopitaco.service.PhasePredictionContextService.TableContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pick'em de fase — escrita e leitura individual. A janela (aberta/travada/não-pronta) e o
 * substrato validável vêm do {@link PhasePredictionContextService}; a pontuação mora no
 * {@code PhasePredictionScoringService}.
 */
@Service
public class PhasePredictionService {

    private final TournamentRepository tournamentRepository;
    private final TournamentPhaseRepository phaseRepository;
    private final TournamentMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final PhasePredictionRepository predictionRepository;
    private final PhasePredictionPositionRepository positionRepository;
    private final PhasePredictionTieRepository tieRepository;
    private final PhasePredictionContextService contextService;
    private final PhasePredictionScoringService scoringService;
    private final PhasePredictionMapper mapper;
    private final MatchMapper matchMapper;
    private final TournamentAccessGuard accessGuard;

    public PhasePredictionService(
            TournamentRepository tournamentRepository,
            TournamentPhaseRepository phaseRepository,
            TournamentMemberRepository memberRepository,
            UserRepository userRepository,
            PhasePredictionRepository predictionRepository,
            PhasePredictionPositionRepository positionRepository,
            PhasePredictionTieRepository tieRepository,
            PhasePredictionContextService contextService,
            PhasePredictionScoringService scoringService,
            PhasePredictionMapper mapper,
            MatchMapper matchMapper,
            TournamentAccessGuard accessGuard
    ) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.predictionRepository = predictionRepository;
        this.positionRepository = positionRepository;
        this.tieRepository = tieRepository;
        this.contextService = contextService;
        this.scoringService = scoringService;
        this.mapper = mapper;
        this.matchMapper = matchMapper;
        this.accessGuard = accessGuard;
    }

    // ---------------------------------------------------------------- template

    @Transactional(readOnly = true)
    public PhasePredictionTemplateResponse template(
            UUID requesterPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId
    ) {
        Tournament tournament = accessGuard.requireViewable(requesterPublicId, tournamentPublicId);
        TournamentPhase phase = loadPhase(tournamentPublicId, phasePublicId);
        PhaseContext ctx = contextService.contextFor(tournament, phase);

        TournamentSettings settings = tournament.getSettings();
        PhasePredictionTemplateResponse.PickemScoring scoring = new PhasePredictionTemplateResponse.PickemScoring(
                settings.getPickemQualifierPoints(),
                settings.getPickemExactPositionPoints(),
                settings.getPickemFirstPlacePoints(),
                settings.getPickemKoMatchupExactPoints(),
                settings.getPickemKoMatchupPartialPoints(),
                settings.getPickemChampionPoints(),
                settings.getPickemRunnerUpPoints(),
                settings.getPickemThirdPlacePoints()
        );

        return new PhasePredictionTemplateResponse(
                phase.getPublicId(),
                phase.getName(),
                phase.getPhaseType(),
                ctx.state(),
                ctx.stateReason(),
                ctx.lockAt(),
                scoring,
                toTableTemplate(ctx.table()),
                toBracketTemplate(phase, ctx.bracket())
        );
    }

    private PhasePredictionTemplateResponse.TableTemplate toTableTemplate(TableContext table) {
        if (table == null) {
            return null;
        }
        List<PhasePredictionTemplateResponse.GroupBlock> blocks = table.blocks().stream()
                .map(b -> new PhasePredictionTemplateResponse.GroupBlock(
                        b.group() != null ? b.group().getPublicId() : null,
                        b.group() != null ? b.group().getName() : null,
                        b.qualifyingDepth(),
                        b.teams().stream().map(matchMapper::toTeamRef).toList()
                ))
                .toList();
        return new PhasePredictionTemplateResponse.TableTemplate(table.qualifyingDepth(), blocks);
    }

    private PhasePredictionTemplateResponse.BracketTemplate toBracketTemplate(
            TournamentPhase phase,
            BracketContext bracket
    ) {
        if (bracket == null) {
            return null;
        }
        int firstRoundCount = bracket.firstRoundTies().size();
        int totalRounds = bracket.totalRounds();
        List<PhasePredictionTemplateResponse.TemplateRound> rounds = new ArrayList<>(totalRounds);
        for (int r = 1; r <= totalRounds; r++) {
            int slots = PhasePredictionContextService.slotsForRound(firstRoundCount, r, totalRounds);
            List<PhasePredictionTemplateResponse.TemplateSlot> slotDtos = new ArrayList<>(slots);
            if (r == 1) {
                for (RealTie tie : bracket.firstRoundTies()) {
                    slotDtos.add(new PhasePredictionTemplateResponse.TemplateSlot(
                            tie.slotIndex(),
                            matchMapper.toTeamRef(tie.homeTeam()),
                            matchMapper.toTeamRef(tie.awayTeam())
                    ));
                }
            } else {
                for (int s = 0; s < slots; s++) {
                    slotDtos.add(new PhasePredictionTemplateResponse.TemplateSlot(s, null, null));
                }
            }
            rounds.add(new PhasePredictionTemplateResponse.TemplateRound(
                    r, BracketService.labelForRound(slots, r), slotDtos
            ));
        }
        return new PhasePredictionTemplateResponse.BracketTemplate(
                bracket.hasThirdPlace(), phase.getEffectiveBracketMode(), totalRounds, rounds
        );
    }

    // ---------------------------------------------------------------- upsert / delete / getMine

    @Transactional
    public PhasePredictionResponse place(
            UUID userPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId,
            PlacePhasePredictionRequest request
    ) {
        Tournament tournament = loadTournament(tournamentPublicId);
        TournamentPhase phase = loadPhase(tournamentPublicId, phasePublicId);
        ensureActiveMember(tournament, userPublicId);

        PhaseContext ctx = contextService.contextFor(tournament, phase);
        ensureOpen(ctx);

        boolean knockout = phase.getPhaseType() == TournamentPhaseType.KNOCKOUT;
        List<PlacePhasePredictionRequest.PositionPick> positionPicks =
                request.positions() == null ? List.of() : request.positions();
        List<PlacePhasePredictionRequest.TiePick> tiePicks =
                request.ties() == null ? List.of() : request.ties();

        List<ResolvedPositionPick> resolvedPositions = List.of();
        List<ResolvedTiePick> resolvedTies = List.of();
        if (knockout) {
            if (!positionPicks.isEmpty()) {
                throw badRequest("positions only apply to a ROUND_ROBIN/GROUPS phase pick'em");
            }
            if (tiePicks.isEmpty()) {
                throw badRequest("ties are required for a KNOCKOUT phase pick'em");
            }
            resolvedTies = validateTies(ctx.bracket(), tiePicks);
        } else {
            if (!tiePicks.isEmpty()) {
                throw badRequest("ties only apply to a KNOCKOUT phase pick'em");
            }
            if (positionPicks.isEmpty()) {
                throw badRequest("positions are required for a ROUND_ROBIN/GROUPS phase pick'em");
            }
            resolvedPositions = validatePositions(phase, ctx.table(), positionPicks);
        }

        User user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(TournamentNotFoundException::new);

        PhasePrediction prediction = predictionRepository
                .findByPhasePublicIdAndUserPublicId(phasePublicId, userPublicId)
                .orElse(null);
        if (prediction == null) {
            prediction = PhasePrediction.builder()
                    .tournament(tournament)
                    .phase(phase)
                    .user(user)
                    .phaseType(phase.getPhaseType())
                    .points(0)
                    .build();
            prediction = predictionRepository.save(prediction);
        } else {
            // Substituição total dos filhos. O flush força os DELETEs (orphanRemoval) antes dos
            // INSERTs — sem ele o Hibernate enfileiraria os INSERTs primeiro e violaria os índices
            // únicos (mesmo padrão do applyTiebreakCriteria no TournamentService).
            prediction.getPositions().clear();
            prediction.getTies().clear();
            prediction.setPoints(0);
            prediction.setScoredAt(null);
            prediction.setUpdatedAt(Instant.now()); // garante entity dirty => @PreUpdate roda
            predictionRepository.saveAndFlush(prediction);
        }

        for (ResolvedPositionPick pick : resolvedPositions) {
            prediction.getPositions().add(PhasePredictionPosition.builder()
                    .phasePrediction(prediction)
                    .group(pick.group())
                    .team(pick.team())
                    .predictedPosition(pick.position())
                    .build());
        }
        for (ResolvedTiePick pick : resolvedTies) {
            prediction.getTies().add(PhasePredictionTie.builder()
                    .phasePrediction(prediction)
                    .roundNumber(pick.roundNumber())
                    .slotIndex(pick.slotIndex())
                    .matchType(pick.matchType())
                    .predictedHomeTeam(pick.home())
                    .predictedAwayTeam(pick.away())
                    .predictedWinnerTeam(pick.winner())
                    .build());
        }
        prediction = predictionRepository.saveAndFlush(prediction);

        return mapper.toResponse(
                prediction,
                sortedPositions(prediction.getPositions()),
                sortedTies(prediction.getTies())
        );
    }

    // ---------------------------------------------------------------- pendências (home/feed)

    /**
     * Fases em que o usuário <b>ainda pode e ainda não fez</b> o Pick'em — alimenta o card
     * "Palpitão aberto" da home. Universo: torneios ativos em IN_PROGRESS onde ele é member
     * ACTIVE; entra a fase cujo Pick'em está {@code OPEN} (mesmo guard do template — fonte única
     * no {@link PhasePredictionContextService}) e onde ele não tem {@code PhasePrediction}.
     * Ordenado por urgência: {@code lockAt} asc com nulls por último, depois nome do torneio e
     * posição da fase.
     */
    @Transactional(readOnly = true)
    public List<PendingPickemResponse> pendingForUser(UUID userPublicId) {
        List<Tournament> tournaments =
                tournamentRepository.findInProgressWhereUserIsActiveMember(userPublicId);
        if (tournaments.isEmpty()) {
            return List.of();
        }
        Set<Long> alreadyPickedPhaseIds =
                new HashSet<>(predictionRepository.findPhaseIdsByUserPublicId(userPublicId));

        record Entry(PendingPickemResponse dto, int phasePosition) {
        }
        List<Entry> entries = new ArrayList<>();
        for (Tournament tournament : tournaments) {
            List<TournamentPhase> phases = phaseRepository
                    .findAllByTournamentPublicIdOrderByPositionAsc(tournament.getPublicId());
            for (TournamentPhase phase : phases) {
                if (alreadyPickedPhaseIds.contains(phase.getId())) {
                    continue;
                }
                PhaseContext ctx = contextService.contextFor(tournament, phase);
                if (ctx.state() != PickemState.OPEN) {
                    continue;
                }
                entries.add(new Entry(
                        new PendingPickemResponse(
                                tournament.getPublicId(),
                                tournament.getName(),
                                phase.getPublicId(),
                                phase.getName(),
                                phase.getPhaseType(),
                                ctx.lockAt()
                        ),
                        phase.getPosition()
                ));
            }
        }

        entries.sort(Comparator
                .comparing((Entry e) -> e.dto().lockAt(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(e -> e.dto().tournamentName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(Entry::phasePosition));
        return entries.stream().map(Entry::dto).toList();
    }

    // ---------------------------------------------------------------- leitura pública

    /**
     * Todos os Pick'ems da fase, paginados (ordem fixa: pontos desc, nome asc). Sempre visíveis —
     * não há redação de conteúdo (decisão de produto): qualquer requester com acesso de leitura ao
     * torneio vê os palpites completos de todos, mesmo antes da trava.
     */
    @Transactional(readOnly = true)
    public Page<PhasePredictionResponse> listForPhase(
            UUID requesterPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId,
            Pageable pageable
    ) {
        Tournament tournament = accessGuard.requireViewable(requesterPublicId, tournamentPublicId);
        TournamentPhase phase = loadPhase(tournamentPublicId, phasePublicId);
        Page<PhasePrediction> page = predictionRepository.findAllByPhasePublicId(phasePublicId, pageable);

        List<Long> ids = page.getContent().stream().map(PhasePrediction::getId).toList();
        Map<Long, List<PhasePredictionPosition>> positionsById = ids.isEmpty()
                ? Map.of()
                : positionRepository.findAllByPredictionIds(ids).stream()
                        .collect(Collectors.groupingBy(p -> p.getPhasePrediction().getId()));
        Map<Long, List<PhasePredictionTie>> tiesById = ids.isEmpty()
                ? Map.of()
                : tieRepository.findAllByPredictionIds(ids).stream()
                        .collect(Collectors.groupingBy(t -> t.getPhasePrediction().getId()));

        // Snapshot do estado real computado uma única vez para a página inteira.
        PhasePredictionScoringService.Snapshot snapshot = scoringService.snapshotFor(tournament, phase);
        return page.map(pp -> enrichedResponse(
                tournament,
                snapshot,
                pp,
                positionsById.getOrDefault(pp.getId(), List.of()),
                tiesById.getOrDefault(pp.getId(), List.of())
        ));
    }

    /** Pick'em de um participante específico na fase (404 se ele não palpitou). */
    @Transactional(readOnly = true)
    public PhasePredictionResponse getForUser(
            UUID requesterPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId,
            UUID targetUserPublicId
    ) {
        Tournament tournament = accessGuard.requireViewable(requesterPublicId, tournamentPublicId);
        TournamentPhase phase = loadPhase(tournamentPublicId, phasePublicId);
        PhasePrediction prediction = predictionRepository
                .findByPhasePublicIdAndUserPublicId(phasePublicId, targetUserPublicId)
                .orElseThrow(PhasePredictionNotFoundException::new);
        return toResponseWithChildren(tournament, phase, prediction);
    }

    @Transactional(readOnly = true)
    public PhasePredictionResponse getMine(UUID userPublicId, UUID tournamentPublicId, UUID phasePublicId) {
        Tournament tournament = loadTournament(tournamentPublicId);
        ensureActiveMember(tournament, userPublicId);
        TournamentPhase phase = loadPhase(tournamentPublicId, phasePublicId);
        PhasePrediction prediction = predictionRepository
                .findByPhasePublicIdAndUserPublicId(phasePublicId, userPublicId)
                .orElseThrow(PhasePredictionNotFoundException::new);
        return toResponseWithChildren(tournament, phase, prediction);
    }

    /**
     * Recálculo manual da pontuação do Pick'em da fase (owner-only). Rede de segurança para
     * quando o admin muda a pontuação {@code pickem*} com a fase já em andamento — por padrão a
     * mudança só vale do próximo resultado em diante.
     */
    @Transactional
    public PickemRecalculationResponse recalculate(
            UUID ownerPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId
    ) {
        Tournament tournament = loadTournament(tournamentPublicId);
        if (!tournament.getOwner().getPublicId().equals(ownerPublicId)) {
            throw new NotTournamentOwnerException();
        }
        TournamentPhase phase = loadPhase(tournamentPublicId, phasePublicId);
        return new PickemRecalculationResponse(scoringService.recalculateForPhase(phase));
    }

    @Transactional
    public void delete(UUID userPublicId, UUID tournamentPublicId, UUID phasePublicId) {
        Tournament tournament = loadTournament(tournamentPublicId);
        TournamentPhase phase = loadPhase(tournamentPublicId, phasePublicId);
        ensureActiveMember(tournament, userPublicId);
        ensureOpen(contextService.contextFor(tournament, phase));
        PhasePrediction prediction = predictionRepository
                .findByPhasePublicIdAndUserPublicId(phasePublicId, userPublicId)
                .orElseThrow(PhasePredictionNotFoundException::new);
        predictionRepository.delete(prediction);
    }

    // ---------------------------------------------------------------- validações

    private List<ResolvedPositionPick> validatePositions(
            TournamentPhase phase,
            TableContext table,
            List<PlacePhasePredictionRequest.PositionPick> picks
    ) {
        boolean groupsPhase = phase.getPhaseType() == TournamentPhaseType.GROUPS;
        Map<UUID, TableBlock> blocksByGroupId = new HashMap<>();
        for (TableBlock block : table.blocks()) {
            if (block.group() != null) {
                blocksByGroupId.put(block.group().getPublicId(), block);
            }
        }
        TableBlock singleBlock = groupsPhase ? null : table.blocks().get(0);

        Set<String> usedPositions = new HashSet<>();
        Set<String> usedTeams = new HashSet<>();
        List<ResolvedPositionPick> resolved = new ArrayList<>(picks.size());
        for (PlacePhasePredictionRequest.PositionPick pick : picks) {
            TableBlock block;
            if (groupsPhase) {
                if (pick.groupId() == null) {
                    throw badRequest("groupId is required for each position pick in a GROUPS phase");
                }
                block = blocksByGroupId.get(pick.groupId());
                if (block == null) {
                    throw new PhaseGroupNotFoundException();
                }
            } else {
                if (pick.groupId() != null) {
                    throw badRequest("groupId only applies to a GROUPS phase pick'em");
                }
                block = singleBlock;
            }

            Team team = block.teams().stream()
                    .filter(t -> t.getPublicId().equals(pick.teamId()))
                    .findFirst()
                    .orElseThrow(() -> badRequest(
                            "team " + pick.teamId() + " is not registered in this phase/group"));

            if (pick.position() > block.qualifyingDepth()) {
                throw badRequest("position " + pick.position()
                        + " is beyond the qualification range (max " + block.qualifyingDepth() + ")");
            }

            String blockKey = pick.groupId() == null ? "-" : pick.groupId().toString();
            if (!usedPositions.add(blockKey + ":" + pick.position())) {
                throw badRequest("duplicate position " + pick.position() + " in the same group");
            }
            if (!usedTeams.add(blockKey + ":" + team.getId())) {
                throw badRequest("a team cannot appear twice in the same group");
            }
            resolved.add(new ResolvedPositionPick(block.group(), team, pick.position()));
        }
        return resolved;
    }

    private List<ResolvedTiePick> validateTies(
            BracketContext bracket,
            List<PlacePhasePredictionRequest.TiePick> picks
    ) {
        int firstRoundCount = bracket.firstRoundTies().size();
        int totalRounds = bracket.totalRounds();

        // Universo de times do bracket: quem está na 1ª rodada real (o resto da árvore deriva dela).
        Map<UUID, Team> bracketTeams = new HashMap<>();
        for (RealTie tie : bracket.firstRoundTies()) {
            bracketTeams.put(tie.homeTeam().getPublicId(), tie.homeTeam());
            bracketTeams.put(tie.awayTeam().getPublicId(), tie.awayTeam());
        }

        Map<String, ResolvedTiePick> byKey = new LinkedHashMap<>();
        Map<Integer, Set<Long>> teamsPerRound = new HashMap<>();
        for (PlacePhasePredictionRequest.TiePick pick : picks) {
            MatchType type = pick.matchType() == null ? MatchType.REGULAR : pick.matchType();

            if (type == MatchType.THIRD_PLACE) {
                if (!bracket.hasThirdPlace()) {
                    throw badRequest("this phase has no third-place match");
                }
                if (pick.roundNumber() != totalRounds) {
                    throw badRequest("third-place slot must be in the final round (" + totalRounds + ")");
                }
                if (pick.slotIndex() != 0) {
                    throw badRequest("third-place slotIndex must be 0");
                }
            } else {
                if (pick.roundNumber() > totalRounds) {
                    throw badRequest("invalid roundNumber " + pick.roundNumber()
                            + " (bracket has " + totalRounds + " rounds)");
                }
                int slots = PhasePredictionContextService.slotsForRound(
                        firstRoundCount, pick.roundNumber(), totalRounds);
                if (pick.slotIndex() >= slots) {
                    throw badRequest("invalid slotIndex " + pick.slotIndex()
                            + " for round " + pick.roundNumber() + " (max " + (slots - 1) + ")");
                }
            }

            Team home = bracketTeams.get(pick.homeTeamId());
            Team away = bracketTeams.get(pick.awayTeamId());
            Team winner = bracketTeams.get(pick.winnerTeamId());
            if (home == null || away == null || winner == null) {
                throw badRequest("all teams in a bracket pick must be part of this bracket");
            }
            if (home.getId().equals(away.getId())) {
                throw badRequest("a team cannot play against itself");
            }
            if (!winner.getId().equals(home.getId()) && !winner.getId().equals(away.getId())) {
                throw badRequest("winnerTeamId must be one of the two teams of the slot");
            }

            String key = slotKey(pick.roundNumber(), pick.slotIndex(), type);
            ResolvedTiePick resolvedPick = new ResolvedTiePick(
                    pick.roundNumber(), pick.slotIndex(), type, home, away, winner);
            if (byKey.putIfAbsent(key, resolvedPick) != null) {
                throw badRequest("duplicate bracket slot in request (round " + pick.roundNumber()
                        + ", slot " + pick.slotIndex() + ")");
            }

            if (type == MatchType.REGULAR) {
                Set<Long> roundTeams = teamsPerRound.computeIfAbsent(pick.roundNumber(), k -> new HashSet<>());
                if (!roundTeams.add(home.getId()) || !roundTeams.add(away.getId())) {
                    throw badRequest("a team cannot appear twice in the same round");
                }
                if (pick.roundNumber() == 1) {
                    RealTie real = bracket.firstRoundTies().get(pick.slotIndex());
                    boolean samePair = pairEquals(
                            home.getId(), away.getId(),
                            real.homeTeam().getId(), real.awayTeam().getId());
                    if (!samePair) {
                        throw badRequest("round 1 matchups are fixed; slot " + pick.slotIndex()
                                + " must match the real matchup");
                    }
                }
            }
        }

        validateBracketCoherence(byKey, totalRounds);
        return new ArrayList<>(byKey.values());
    }

    /**
     * Coerência da árvore prevista: um slot de rodada {@code r >= 2} deve ser formado pelos
     * vencedores que o próprio usuário escolheu nos slots-filho ({@code 2j} alimenta o mandante,
     * {@code 2j+1} o visitante). Checado apenas quando o filho foi enviado (palpite parcial é
     * permitido). O slot de 3º lugar deve conter os perdedores previstos das semifinais.
     */
    private void validateBracketCoherence(Map<String, ResolvedTiePick> byKey, int totalRounds) {
        for (ResolvedTiePick pick : byKey.values()) {
            if (pick.matchType() != MatchType.REGULAR || pick.roundNumber() < 2) {
                continue;
            }
            ResolvedTiePick childHome = byKey.get(
                    slotKey(pick.roundNumber() - 1, pick.slotIndex() * 2, MatchType.REGULAR));
            ResolvedTiePick childAway = byKey.get(
                    slotKey(pick.roundNumber() - 1, pick.slotIndex() * 2 + 1, MatchType.REGULAR));
            if (childHome != null && !pick.home().getId().equals(childHome.winner().getId())) {
                throw badRequest("round " + pick.roundNumber() + " slot " + pick.slotIndex()
                        + " must be formed by the winners you picked in round " + (pick.roundNumber() - 1));
            }
            if (childAway != null && !pick.away().getId().equals(childAway.winner().getId())) {
                throw badRequest("round " + pick.roundNumber() + " slot " + pick.slotIndex()
                        + " must be formed by the winners you picked in round " + (pick.roundNumber() - 1));
            }
        }

        ResolvedTiePick thirdPlace = byKey.get(slotKey(totalRounds, 0, MatchType.THIRD_PLACE));
        if (thirdPlace == null) {
            return;
        }
        ResolvedTiePick finalPick = byKey.get(slotKey(totalRounds, 0, MatchType.REGULAR));
        if (finalPick != null) {
            boolean overlap = thirdPlace.home().getId().equals(finalPick.home().getId())
                    || thirdPlace.home().getId().equals(finalPick.away().getId())
                    || thirdPlace.away().getId().equals(finalPick.home().getId())
                    || thirdPlace.away().getId().equals(finalPick.away().getId());
            if (overlap) {
                throw badRequest("third-place teams cannot appear in the final");
            }
        }
        if (totalRounds >= 2) {
            ResolvedTiePick sf0 = byKey.get(slotKey(totalRounds - 1, 0, MatchType.REGULAR));
            ResolvedTiePick sf1 = byKey.get(slotKey(totalRounds - 1, 1, MatchType.REGULAR));
            if (sf0 != null && sf1 != null) {
                Long loser0 = loserOf(sf0);
                Long loser1 = loserOf(sf1);
                boolean matches = pairEquals(
                        thirdPlace.home().getId(), thirdPlace.away().getId(), loser0, loser1);
                if (!matches) {
                    throw badRequest("third-place matchup must be the losers of your semifinals");
                }
            }
        }
    }

    private Long loserOf(ResolvedTiePick pick) {
        return pick.winner().getId().equals(pick.home().getId())
                ? pick.away().getId()
                : pick.home().getId();
    }

    private boolean pairEquals(Long a1, Long a2, Long b1, Long b2) {
        return (a1.equals(b1) && a2.equals(b2)) || (a1.equals(b2) && a2.equals(b1));
    }

    private String slotKey(int round, int slot, MatchType type) {
        return round + ":" + slot + ":" + type;
    }

    // ---------------------------------------------------------------- apoio

    /** Mapeia com o breakdown de acertos (D4) calculado contra o estado real da fase. */
    private PhasePredictionResponse toResponseWithChildren(
            Tournament tournament,
            TournamentPhase phase,
            PhasePrediction prediction
    ) {
        List<PhasePredictionPosition> positions =
                positionRepository.findAllByPredictionIds(List.of(prediction.getId()));
        List<PhasePredictionTie> ties =
                tieRepository.findAllByPredictionIds(List.of(prediction.getId()));
        PhasePredictionScoringService.Snapshot snapshot = scoringService.snapshotFor(tournament, phase);
        return enrichedResponse(tournament, snapshot, prediction, positions, ties);
    }

    private PhasePredictionResponse enrichedResponse(
            Tournament tournament,
            PhasePredictionScoringService.Snapshot snapshot,
            PhasePrediction prediction,
            List<PhasePredictionPosition> positions,
            List<PhasePredictionTie> ties
    ) {
        PhasePredictionScoringService.Breakdown breakdown = scoringService.breakdownFor(
                prediction, positions, ties, snapshot, tournament.getSettings());
        return mapper.toResponse(
                prediction,
                sortedPositions(positions),
                sortedTies(ties),
                breakdown.positionOutcomes(),
                breakdown.tieOutcomes(),
                breakdown.terminals()
        );
    }

    private List<PhasePredictionPosition> sortedPositions(List<PhasePredictionPosition> positions) {
        return positions.stream()
                .sorted(Comparator
                        .comparingInt((PhasePredictionPosition p) ->
                                p.getGroup() == null ? -1 : p.getGroup().getPosition())
                        .thenComparingInt(PhasePredictionPosition::getPredictedPosition))
                .toList();
    }

    private List<PhasePredictionTie> sortedTies(List<PhasePredictionTie> ties) {
        return ties.stream()
                .sorted(Comparator
                        .comparingInt(PhasePredictionTie::getRoundNumber)
                        .thenComparing(PhasePredictionTie::getMatchType)
                        .thenComparingInt(PhasePredictionTie::getSlotIndex))
                .toList();
    }

    private void ensureOpen(PhaseContext ctx) {
        if (ctx.state() == PickemState.OPEN) {
            return;
        }
        if (ctx.state() == PickemState.LOCKED) {
            throw new PhasePredictionLockedException("Phase pick'em is locked (the phase has started)");
        }
        if (PhasePredictionContextService.REASON_TOURNAMENT_NOT_IN_PROGRESS.equals(ctx.stateReason())) {
            throw new PhasePredictionLockedException(
                    "Phase pick'em is only accepted while tournament is IN_PROGRESS");
        }
        throw new PhasePredictionLockedException(
                "Phase pick'em is not available yet: " + ctx.stateReason());
    }

    private Tournament loadTournament(UUID tournamentPublicId) {
        return tournamentRepository.findByPublicIdAndActiveTrue(tournamentPublicId)
                .orElseThrow(TournamentNotFoundException::new);
    }

    private TournamentPhase loadPhase(UUID tournamentPublicId, UUID phasePublicId) {
        return phaseRepository
                .findByPublicIdAndTournamentPublicId(phasePublicId, tournamentPublicId)
                .orElseThrow(PhaseNotFoundException::new);
    }

    private void ensureActiveMember(Tournament tournament, UUID userPublicId) {
        TournamentMember member = memberRepository
                .findByTournamentPublicIdAndUserPublicId(tournament.getPublicId(), userPublicId)
                .orElseThrow(NotTournamentMemberException::new);
        if (member.getStatus() != TournamentMemberStatus.ACTIVE) {
            throw new NotTournamentMemberException();
        }
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST);
    }

    private record ResolvedPositionPick(PhaseGroup group, Team team, int position) {
    }

    private record ResolvedTiePick(
            int roundNumber,
            int slotIndex,
            MatchType matchType,
            Team home,
            Team away,
            Team winner
    ) {
    }
}
