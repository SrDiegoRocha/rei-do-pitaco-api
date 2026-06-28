package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.response.RankingRowResponse;
import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.Prediction;
import com.example.reidopitaco.entity.User;
import com.example.reidopitaco.enums.MatchStatus;
import com.example.reidopitaco.enums.MatchType;
import com.example.reidopitaco.enums.TournamentMemberStatus;
import com.example.reidopitaco.exception.BusinessException;
import com.example.reidopitaco.exception.PhaseGroupNotFoundException;
import com.example.reidopitaco.exception.PhaseNotFoundException;
import com.example.reidopitaco.repository.PhaseGroupRepository;
import com.example.reidopitaco.repository.PredictionRepository;
import com.example.reidopitaco.repository.TournamentPhaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RankingService {

    private final PredictionRepository predictionRepository;
    private final TournamentPhaseRepository phaseRepository;
    private final PhaseGroupRepository groupRepository;
    private final TournamentAccessGuard accessGuard;
    private final AvatarService avatarService;

    public RankingService(
            PredictionRepository predictionRepository,
            TournamentPhaseRepository phaseRepository,
            PhaseGroupRepository groupRepository,
            TournamentAccessGuard accessGuard,
            AvatarService avatarService
    ) {
        this.predictionRepository = predictionRepository;
        this.phaseRepository = phaseRepository;
        this.groupRepository = groupRepository;
        this.accessGuard = accessGuard;
        this.avatarService = avatarService;
    }

    /**
     * Ranking do torneio, com filtros opcionais por fase, grupo, rodada, tipo de
     * partida e status de membro. Sem filtro, agrega todas as predictions. Com filtro,
     * considera só os palpites das partidas que casam — útil para "ranking só da fase de
     * grupos", "só do Grupo A", "só da rodada 3", "só da Final" vs "só da Disputa de 3º"
     * (mesmo round, matchType diferente). O {@code memberStatus} restringe a quem ainda
     * está no torneio com aquele status (ex.: só {@code ACTIVE} esconde quem saiu/foi
     * banido); {@code null} inclui todos que palpitaram. {@code totalPredictions}/pontos
     * refletem o recorte.
     */
    @Transactional(readOnly = true)
    public List<RankingRowResponse> compute(
            UUID requesterPublicId,
            UUID tournamentPublicId,
            UUID phaseId,
            UUID groupId,
            Integer round,
            MatchType matchType,
            TournamentMemberStatus memberStatus
    ) {
        accessGuard.requireViewable(requesterPublicId, tournamentPublicId);

        // Valida os filtros: 404 se o id não pertence ao torneio/fase; 400 se grupo sem fase.
        if (phaseId != null) {
            phaseRepository.findByPublicIdAndTournamentPublicId(phaseId, tournamentPublicId)
                    .orElseThrow(PhaseNotFoundException::new);
        }
        if (groupId != null) {
            if (phaseId == null) {
                throw new BusinessException(
                        "groupId requires phaseId (a group belongs to a phase)",
                        HttpStatus.BAD_REQUEST
                );
            }
            groupRepository.findByPublicIdAndPhasePublicId(groupId, phaseId)
                    .orElseThrow(PhaseGroupNotFoundException::new);
        }

        List<Prediction> predictions = predictionRepository.findForRanking(
                tournamentPublicId, phaseId, groupId, round, matchType, memberStatus);

        Map<Long, Accumulator> table = new HashMap<>();
        for (Prediction p : predictions) {
            Accumulator acc = table.computeIfAbsent(
                    p.getUser().getId(),
                    k -> new Accumulator(p.getUser())
            );
            Match match = p.getMatch();
            acc.totalPredictions++;
            if (match.getStatus() != MatchStatus.COMPLETED) continue;
            acc.totalPoints += p.getPoints();
            Integer actualHome = match.getHomeScore();
            Integer actualAway = match.getAwayScore();
            if (actualHome == null || actualAway == null) continue;
            if (p.getHomeScore() == actualHome && p.getAwayScore() == actualAway) {
                acc.exactScoreHits++;
            } else if (Integer.compare(p.getHomeScore(), p.getAwayScore())
                    == Integer.compare(actualHome, actualAway)) {
                acc.winnerHits++;
            } else {
                acc.wrongs++;
            }
        }

        List<Accumulator> ordered = new ArrayList<>(table.values());
        ordered.sort(
                Comparator.comparingInt((Accumulator a) -> a.totalPoints).reversed()
                        .thenComparing(Comparator.comparingInt((Accumulator a) -> a.exactScoreHits).reversed())
                        .thenComparing(Comparator.comparingInt((Accumulator a) -> a.winnerHits).reversed())
                        .thenComparing(Comparator.comparingInt((Accumulator a) -> a.wrongs))
                        .thenComparing((a, b) -> a.user.getName().compareToIgnoreCase(b.user.getName()))
        );

        List<RankingRowResponse> rows = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            Accumulator a = ordered.get(i);
            rows.add(new RankingRowResponse(
                    i + 1,
                    a.user.getPublicId(),
                    a.user.getName(),
                    avatarService.avatarUrlFor(a.user.getName()),
                    a.totalPoints,
                    a.exactScoreHits,
                    a.winnerHits,
                    a.wrongs,
                    a.totalPredictions
            ));
        }
        return rows;
    }

    static class Accumulator {
        final User user;
        int totalPoints;
        int exactScoreHits;
        int winnerHits;
        int wrongs;
        int totalPredictions;

        Accumulator(User user) {
            this.user = user;
        }
    }
}
