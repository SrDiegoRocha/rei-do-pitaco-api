package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.response.ParticipantSummaryResponse;
import com.example.reidopitaco.dto.response.RankingRowResponse;
import com.example.reidopitaco.entity.PhasePrediction;
import com.example.reidopitaco.entity.PhasePredictionPosition;
import com.example.reidopitaco.entity.PhasePredictionTie;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.User;
import com.example.reidopitaco.exception.BusinessException;
import com.example.reidopitaco.repository.PhasePredictionPositionRepository;
import com.example.reidopitaco.repository.PhasePredictionRepository;
import com.example.reidopitaco.repository.PhasePredictionTieRepository;
import com.example.reidopitaco.repository.TournamentMemberRepository;
import com.example.reidopitaco.repository.UserRepository;
import com.example.reidopitaco.service.PhasePredictionScoringService.ComponentTotals;
import com.example.reidopitaco.service.PhasePredictionScoringService.Snapshot;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Perfil do palpiteiro no torneio (D1): o ranking mostra só o total somado; aqui o desempenho vem
 * explicado e separado — pontos de palpite de partida vs pontos de Pick'em, com a decomposição do
 * Pick'em por fase e por componente. Tudo derivado das mesmas fontes do ranking e da pontuação
 * (nunca diverge).
 */
@Service
public class ParticipantSummaryService {

    private final RankingService rankingService;
    private final PhasePredictionRepository phasePredictionRepository;
    private final PhasePredictionPositionRepository positionRepository;
    private final PhasePredictionTieRepository tieRepository;
    private final PhasePredictionScoringService scoringService;
    private final TournamentMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final TournamentAccessGuard accessGuard;
    private final AvatarService avatarService;

    public ParticipantSummaryService(
            RankingService rankingService,
            PhasePredictionRepository phasePredictionRepository,
            PhasePredictionPositionRepository positionRepository,
            PhasePredictionTieRepository tieRepository,
            PhasePredictionScoringService scoringService,
            TournamentMemberRepository memberRepository,
            UserRepository userRepository,
            TournamentAccessGuard accessGuard,
            AvatarService avatarService
    ) {
        this.rankingService = rankingService;
        this.phasePredictionRepository = phasePredictionRepository;
        this.positionRepository = positionRepository;
        this.tieRepository = tieRepository;
        this.scoringService = scoringService;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.accessGuard = accessGuard;
        this.avatarService = avatarService;
    }

    @Transactional(readOnly = true)
    public ParticipantSummaryResponse summary(
            UUID requesterPublicId,
            UUID tournamentPublicId,
            UUID targetUserPublicId
    ) {
        Tournament tournament = accessGuard.requireViewable(requesterPublicId, tournamentPublicId);

        // O alvo precisa ser (ou ter sido) participante do torneio; 404 caso contrário.
        memberRepository
                .findByTournamentPublicIdAndUserPublicId(tournamentPublicId, targetUserPublicId)
                .orElseThrow(() -> new BusinessException(
                        "Participant not found in this tournament", HttpStatus.NOT_FOUND));
        User user = userRepository.findByPublicId(targetUserPublicId)
                .orElseThrow(() -> new BusinessException(
                        "Participant not found in this tournament", HttpStatus.NOT_FOUND));

        // Posição e total: mesma fonte do ranking (sem filtros). matchBreakdown sai da mesma linha.
        List<RankingRowResponse> ranking = rankingService.compute(
                requesterPublicId, tournamentPublicId, null, null, null, null, null);
        RankingRowResponse row = ranking.stream()
                .filter(r -> r.userId().equals(targetUserPublicId))
                .findFirst()
                .orElse(null);

        // Pick'em por fase, decomposto por componente contra o estado real atual.
        List<PhasePrediction> pickems = phasePredictionRepository
                .findAllByTournamentPublicIdAndUserPublicId(tournamentPublicId, targetUserPublicId)
                .stream()
                .sorted(Comparator.comparingInt(pp -> pp.getPhase().getPosition()))
                .toList();
        List<Long> ids = pickems.stream().map(PhasePrediction::getId).toList();
        Map<Long, List<PhasePredictionPosition>> positionsById = ids.isEmpty()
                ? Map.of()
                : positionRepository.findAllByPredictionIds(ids).stream()
                        .collect(Collectors.groupingBy(p -> p.getPhasePrediction().getId()));
        Map<Long, List<PhasePredictionTie>> tiesById = ids.isEmpty()
                ? Map.of()
                : tieRepository.findAllByPredictionIds(ids).stream()
                        .collect(Collectors.groupingBy(t -> t.getPhasePrediction().getId()));

        int pickemPoints = 0;
        List<ParticipantSummaryResponse.PickemPhaseBreakdown> byPhase = new ArrayList<>(pickems.size());
        for (PhasePrediction pickem : pickems) {
            Snapshot snapshot = scoringService.snapshotFor(tournament, pickem.getPhase());
            ComponentTotals totals = scoringService.componentTotals(
                    pickem,
                    positionsById.getOrDefault(pickem.getId(), List.of()),
                    tiesById.getOrDefault(pickem.getId(), List.of()),
                    snapshot,
                    tournament.getSettings()
            );
            pickemPoints += pickem.getPoints();
            byPhase.add(new ParticipantSummaryResponse.PickemPhaseBreakdown(
                    pickem.getPhase().getPublicId(),
                    pickem.getPhase().getName(),
                    pickem.getPhaseType(),
                    pickem.getPhase().getFinalizedAt() == null,
                    pickem.getPoints(),
                    new ParticipantSummaryResponse.Components(
                            totals.qualifier(),
                            totals.exactPosition(),
                            totals.firstPlace(),
                            totals.koMatchupExact(),
                            totals.koMatchupPartial(),
                            totals.champion(),
                            totals.runnerUp(),
                            totals.thirdPlace()
                    )
            ));
        }

        int totalPoints = row != null ? row.totalPoints() : pickemPoints;
        return new ParticipantSummaryResponse(
                user.getPublicId(),
                user.getName(),
                avatarService.avatarUrlFor(user.getName()),
                row != null ? row.position() : null,
                totalPoints,
                totalPoints - pickemPoints,
                pickemPoints,
                new ParticipantSummaryResponse.MatchBreakdown(
                        row != null ? row.totalPredictions() : 0,
                        row != null ? row.exactScoreHits() : 0,
                        row != null ? row.winnerHits() : 0,
                        row != null ? row.wrongs() : 0
                ),
                byPhase
        );
    }
}
