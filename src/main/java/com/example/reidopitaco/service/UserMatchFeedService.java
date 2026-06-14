package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.response.PendingPredictionsCountResponse;
import com.example.reidopitaco.dto.response.UserMatchResponse;
import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.Prediction;
import com.example.reidopitaco.mapper.UserMatchMapper;
import com.example.reidopitaco.repository.MatchRepository;
import com.example.reidopitaco.repository.PredictionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Feed pessoal de partidas — alimenta a tela inicial do app, que lista todas as partidas
 * (com horário marcado) dos torneios em que o usuário participa, em ordem cronológica.
 *
 * <p>Não usa {@code TournamentAccessGuard}: o próprio escopo da query (membro ACTIVE de
 * torneio ativo) já é o controle de acesso — só vêm partidas de torneios que o usuário
 * participa. Partidas sem {@code scheduledAt} são excluídas, por não terem lugar numa
 * timeline por data.
 */
@Service
public class UserMatchFeedService {

    /** Sentinelas para a janela de data aberta — comparadas contra a coluna no lugar de NULL. */
    private static final Instant MIN_SCHEDULED_AT = Instant.EPOCH; // 1970-01-01T00:00:00Z
    private static final Instant MAX_SCHEDULED_AT = Instant.parse("9999-12-31T23:59:59Z");

    private final MatchRepository matchRepository;
    private final PredictionRepository predictionRepository;
    private final UserMatchMapper mapper;

    public UserMatchFeedService(
            MatchRepository matchRepository,
            PredictionRepository predictionRepository,
            UserMatchMapper mapper
    ) {
        this.matchRepository = matchRepository;
        this.predictionRepository = predictionRepository;
        this.mapper = mapper;
    }

    /**
     * Lista o feed do usuário. {@code from}/{@code to} recortam por {@code scheduledAt}
     * na janela semiaberta {@code [from, to)} (cada um ignorado se {@code null}).
     * {@code limit} limita o número de itens; {@code null} (ou {@code <= 0}) = sem teto,
     * mantendo o comportamento retrocompatível de "lista inteira".
     */
    @Transactional(readOnly = true)
    public List<UserMatchResponse> listForUser(UUID userPublicId, Instant from, Instant to, Integer limit) {
        Pageable pageable = (limit != null && limit > 0)
                ? PageRequest.of(0, limit)
                : Pageable.unpaged();

        Instant effectiveFrom = from != null ? from : MIN_SCHEDULED_AT;
        Instant effectiveTo = to != null ? to : MAX_SCHEDULED_AT;

        List<Match> matches = matchRepository.findScheduledForUserFeed(
                userPublicId, effectiveFrom, effectiveTo, pageable);
        if (matches.isEmpty()) {
            return List.of();
        }

        List<Long> matchIds = matches.stream().map(Match::getId).toList();
        Map<Long, Prediction> predictionByMatchId = predictionRepository
                .findByUserPublicIdAndMatchIdIn(userPublicId, matchIds)
                .stream()
                .collect(Collectors.toMap(p -> p.getMatch().getId(), Function.identity()));

        return matches.stream()
                .map(match -> mapper.toResponse(match, predictionByMatchId.get(match.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PendingPredictionsCountResponse pendingPredictionsCount(UUID userPublicId) {
        long count = matchRepository.countPendingPredictionsForUser(userPublicId, Instant.now());
        return new PendingPredictionsCountResponse(count);
    }
}
