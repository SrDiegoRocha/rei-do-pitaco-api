package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.request.PlacePredictionRequest;
import com.example.reidopitaco.dto.response.PredictionResponse;
import com.example.reidopitaco.dto.response.PredictionStatsResponse;
import com.example.reidopitaco.dto.response.RecalculationResponse;
import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.Prediction;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentMember;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.entity.TournamentSettings;
import com.example.reidopitaco.entity.User;
import com.example.reidopitaco.enums.MatchLegMode;
import com.example.reidopitaco.enums.MatchSide;
import com.example.reidopitaco.enums.MatchStatus;
import com.example.reidopitaco.enums.TournamentMemberStatus;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.enums.TournamentStatus;
import com.example.reidopitaco.exception.BusinessException;
import com.example.reidopitaco.exception.MatchNotFoundException;
import com.example.reidopitaco.exception.NotTournamentMemberException;
import com.example.reidopitaco.exception.NotTournamentOwnerException;
import com.example.reidopitaco.exception.PredictionLockedException;
import com.example.reidopitaco.exception.PredictionNotFoundException;
import com.example.reidopitaco.exception.TournamentNotFoundException;
import com.example.reidopitaco.mapper.PredictionMapper;
import com.example.reidopitaco.repository.MatchRepository;
import com.example.reidopitaco.repository.PredictionRepository;
import com.example.reidopitaco.repository.TournamentMemberRepository;
import com.example.reidopitaco.repository.TournamentRepository;
import com.example.reidopitaco.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PredictionService {

    private final TournamentRepository tournamentRepository;
    private final TournamentMemberRepository memberRepository;
    private final MatchRepository matchRepository;
    private final PredictionRepository predictionRepository;
    private final UserRepository userRepository;
    private final PredictionMapper mapper;
    private final TieAggregateCalculator tieCalculator;
    private final MatchPenaltyHelper penaltyHelper;
    private final MatchLegModeResolver legModeResolver;

    public PredictionService(
            TournamentRepository tournamentRepository,
            TournamentMemberRepository memberRepository,
            MatchRepository matchRepository,
            PredictionRepository predictionRepository,
            UserRepository userRepository,
            PredictionMapper mapper,
            TieAggregateCalculator tieCalculator,
            MatchPenaltyHelper penaltyHelper,
            MatchLegModeResolver legModeResolver
    ) {
        this.tournamentRepository = tournamentRepository;
        this.memberRepository = memberRepository;
        this.matchRepository = matchRepository;
        this.predictionRepository = predictionRepository;
        this.userRepository = userRepository;
        this.mapper = mapper;
        this.tieCalculator = tieCalculator;
        this.penaltyHelper = penaltyHelper;
        this.legModeResolver = legModeResolver;
    }

    @Transactional
    public PredictionResponse place(
            UUID userPublicId,
            UUID tournamentPublicId,
            UUID matchPublicId,
            PlacePredictionRequest request
    ) {
        Tournament tournament = loadTournament(tournamentPublicId);
        ensureTournamentInProgress(tournament);
        ensureActiveMember(tournament, userPublicId);

        Match match = loadMatchInTournament(tournament, matchPublicId);
        ensurePredictionsOpen(match);
        validatePredictionCascade(match, request);

        User user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(TournamentNotFoundException::new);

        Prediction prediction = predictionRepository
                .findByMatchPublicIdAndUserPublicId(matchPublicId, userPublicId)
                .orElse(null);

        if (prediction == null) {
            prediction = Prediction.builder()
                    .tournament(tournament)
                    .match(match)
                    .user(user)
                    .homeScore(request.homeScore())
                    .awayScore(request.awayScore())
                    .homeExtraTimeScore(request.homeExtraTimeScore())
                    .awayExtraTimeScore(request.awayExtraTimeScore())
                    .penaltyWinner(request.penaltyWinner())
                    .points(0)
                    .build();
        } else {
            prediction.setHomeScore(request.homeScore());
            prediction.setAwayScore(request.awayScore());
            prediction.setHomeExtraTimeScore(request.homeExtraTimeScore());
            prediction.setAwayExtraTimeScore(request.awayExtraTimeScore());
            prediction.setPenaltyWinner(request.penaltyWinner());
        }

        return mapper.toResponse(predictionRepository.saveAndFlush(prediction));
    }

    @Transactional(readOnly = true)
    public List<PredictionResponse> listMine(UUID userPublicId, UUID tournamentPublicId) {
        Tournament tournament = loadTournament(tournamentPublicId);
        ensureActiveMember(tournament, userPublicId);
        return predictionRepository.findAllByTournamentPublicIdAndUserPublicId(
                        tournamentPublicId, userPublicId
                )
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    /**
     * Palpites de um participante arbitrário no torneio inteiro. Diferente de {@link #listMine}
     * (os meus, sempre visíveis), aqui cada palpite é redigido conforme a janela da sua partida —
     * placares/pontos só aparecem quando já podem ser revelados (após `scheduledAt`, ou após o
     * resultado quando não há data). Acesso: owner ou member ACTIVE. Usuário sem palpites → `[]`.
     */
    @Transactional(readOnly = true)
    public List<PredictionResponse> listForUserInTournament(
            UUID requesterPublicId,
            UUID tournamentPublicId,
            UUID targetUserPublicId
    ) {
        Tournament tournament = loadTournament(tournamentPublicId);
        boolean isOwner = tournament.getOwner().getPublicId().equals(requesterPublicId);
        if (!isOwner) {
            ensureActiveMember(tournament, requesterPublicId);
        }
        return predictionRepository
                .findAllByTournamentPublicIdAndUserPublicId(tournamentPublicId, targetUserPublicId)
                .stream()
                .map(p -> mapper.toResponse(p, canRevealPredictionScores(p.getMatch())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PredictionResponse> listForMatch(
            UUID requesterPublicId,
            UUID tournamentPublicId,
            UUID matchPublicId
    ) {
        Tournament tournament = loadTournament(tournamentPublicId);
        boolean isOwner = tournament.getOwner().getPublicId().equals(requesterPublicId);
        if (!isOwner) {
            ensureActiveMember(tournament, requesterPublicId);
        }
        Match match = loadMatchInTournament(tournament, matchPublicId);

        boolean revealScores = canRevealPredictionScores(match);
        return predictionRepository.findAllByMatchPublicId(matchPublicId)
                .stream()
                .map(p -> mapper.toResponse(p, revealScores))
                .toList();
    }

    /**
     * Distribuição agregada dos palpites do match (mandante/empate/visitante), sem expor palpites
     * individuais. Nunca falha por janela de tempo — pode ser chamado a qualquer momento. Acesso:
     * owner ou member ACTIVE (mesma regra do listing).
     */
    @Transactional(readOnly = true)
    public PredictionStatsResponse stats(
            UUID requesterPublicId,
            UUID tournamentPublicId,
            UUID matchPublicId
    ) {
        Tournament tournament = loadTournament(tournamentPublicId);
        boolean isOwner = tournament.getOwner().getPublicId().equals(requesterPublicId);
        if (!isOwner) {
            ensureActiveMember(tournament, requesterPublicId);
        }
        loadMatchInTournament(tournament, matchPublicId); // 404 se o match não for deste torneio

        long homeWin = 0;
        long draw = 0;
        long awayWin = 0;
        for (Prediction p : predictionRepository.findAllByMatchPublicId(matchPublicId)) {
            // Desfecho palpitado: usa o placar da prorrogação quando informado (mais preciso em
            // mata-mata de jogo único — ex.: palpite 1x1 no normal e 2x1 na prorrogação conta como
            // vitória do mandante, não empate), senão cai no placar do tempo normal.
            int predHome = p.getHomeExtraTimeScore() != null ? p.getHomeExtraTimeScore() : p.getHomeScore();
            int predAway = p.getAwayExtraTimeScore() != null ? p.getAwayExtraTimeScore() : p.getAwayScore();
            int cmp = Integer.compare(predHome, predAway);
            if (cmp > 0) {
                homeWin++;
            } else if (cmp < 0) {
                awayWin++;
            } else {
                draw++;
            }
        }
        long total = homeWin + draw + awayWin;
        int[] pct = roundedPercentages(total, new long[]{homeWin, draw, awayWin});
        return new PredictionStatsResponse(total, homeWin, draw, awayWin, pct[0], pct[1], pct[2]);
    }

    /**
     * Converte contagens em percentuais inteiros que somam exatamente 100 (método do maior resto).
     * {@code total <= 0} → tudo zero.
     */
    private int[] roundedPercentages(long total, long[] counts) {
        int n = counts.length;
        int[] result = new int[n];
        if (total <= 0) {
            return result;
        }
        double[] remainder = new double[n];
        int assigned = 0;
        for (int i = 0; i < n; i++) {
            double exact = counts[i] * 100.0 / total;
            result[i] = (int) Math.floor(exact);
            remainder[i] = exact - result[i];
            assigned += result[i];
        }
        int leftover = 100 - assigned;
        for (int k = 0; k < leftover; k++) {
            int best = 0;
            double bestRemainder = -1;
            for (int i = 0; i < n; i++) {
                if (remainder[i] > bestRemainder) {
                    bestRemainder = remainder[i];
                    best = i;
                }
            }
            result[best]++;
            remainder[best] = -1; // não escolher o mesmo bucket de novo
        }
        return result;
    }

    private boolean canRevealPredictionScores(Match match) {
        if (match.getScheduledAt() != null) {
            return !Instant.now().isBefore(match.getScheduledAt());
        }
        return match.getStatus() == MatchStatus.COMPLETED;
    }

    @Transactional
    public void delete(UUID userPublicId, UUID tournamentPublicId, UUID matchPublicId) {
        Tournament tournament = loadTournament(tournamentPublicId);
        ensureActiveMember(tournament, userPublicId);
        Match match = loadMatchInTournament(tournament, matchPublicId);
        ensurePredictionsOpen(match);
        Prediction prediction = predictionRepository
                .findByMatchPublicIdAndUserPublicId(matchPublicId, userPublicId)
                .orElseThrow(PredictionNotFoundException::new);
        predictionRepository.delete(prediction);
    }

    /**
     * Janela de palpite. Dois modos, conforme a partida tenha ou não horário definido:
     * <ul>
     *   <li><b>Com {@code scheduledAt}</b>: aceita palpite até o horário marcado; depois trava
     *       (o jogo começou). O resultado real só pode ser lançado após esse horário.</li>
     *   <li><b>Sem {@code scheduledAt}</b>: aceita palpite até o resultado real ser lançado
     *       (match vira {@code COMPLETED}); a partir daí trava.</li>
     * </ul>
     * Partida {@code CANCELLED} nunca aceita palpite.
     */
    private void ensurePredictionsOpen(Match match) {
        if (match.getStatus() == MatchStatus.CANCELLED) {
            throw new PredictionLockedException("Match is cancelled");
        }
        if (match.getScheduledAt() != null) {
            if (!Instant.now().isBefore(match.getScheduledAt())) {
                throw new PredictionLockedException("Predictions are locked for this match");
            }
        } else if (match.getStatus() == MatchStatus.COMPLETED) {
            throw new PredictionLockedException(
                    "Predictions are locked: the match result has already been set"
            );
        }
    }

    @Transactional
    public void recalculatePointsFor(Match match) {
        List<Prediction> predictions = predictionRepository.findAllByMatchId(match.getId());
        if (predictions.isEmpty()) {
            return;
        }
        TournamentSettings settings = match.getPhase().getTournament().getSettings();
        for (Prediction p : predictions) {
            p.setPoints(scoreFor(p, match, settings));
        }
        predictionRepository.saveAll(predictions);
    }

    /**
     * Reaplica as regras de pontuação vigentes do torneio a <b>todos</b> os palpites já existentes,
     * partida por partida. Pensado para quando o owner altera a pontuação
     * ({@code exactScorePoints}/{@code winnerPoints}/{@code wrongPoints}) com o torneio já em
     * andamento: por padrão a mudança só vale para resultados lançados dali em diante, então este
     * endpoint serve para o owner decidir, explicitamente, recalcular o histórico também.
     *
     * <p>Owner-only. Percorre todas as partidas do torneio e recomputa {@code prediction.points}
     * com {@link #scoreFor}, usando os {@code TournamentSettings} atuais. Partidas não-{@code COMPLETED}
     * (incluindo {@code CANCELLED}) zeram, como já fazem o {@code setResult}/{@code cancel}. Idempotente:
     * rodar de novo sem mudar nada não altera nenhum ponto. O ranking, calculado on-demand a partir de
     * {@code prediction.points}, reflete o novo cenário na próxima leitura.
     */
    @Transactional
    public RecalculationResponse recalculateAllPoints(UUID ownerPublicId, UUID tournamentPublicId) {
        Tournament tournament = loadOwnedTournament(ownerPublicId, tournamentPublicId);
        TournamentSettings settings = tournament.getSettings();

        List<Match> matches = matchRepository.findAllByTournamentPublicIdOrdered(tournamentPublicId);
        int matchesProcessed = 0;
        int predictionsUpdated = 0;
        for (Match match : matches) {
            List<Prediction> predictions = predictionRepository.findAllByMatchId(match.getId());
            if (predictions.isEmpty()) {
                continue;
            }
            matchesProcessed++;
            for (Prediction p : predictions) {
                int newPoints = scoreFor(p, match, settings);
                if (p.getPoints() != newPoints) {
                    p.setPoints(newPoints);
                    predictionsUpdated++;
                }
            }
            predictionRepository.saveAll(predictions);
        }
        return new RecalculationResponse(matches.size(), matchesProcessed, predictionsUpdated);
    }

    @Transactional
    public void zeroPointsFor(Match match) {
        List<Prediction> predictions = predictionRepository.findAllByMatchId(match.getId());
        if (predictions.isEmpty()) {
            return;
        }
        for (Prediction p : predictions) {
            p.setPoints(0);
        }
        predictionRepository.saveAll(predictions);
    }

    /**
     * Pontuação de um palpite. Os componentes são <b>somados</b> (não substituem uns aos outros):
     * <ol>
     *   <li><b>Tempo normal</b>: placar exato → {@code exactScorePoints}; senão acerto do
     *       vencedor/empate → {@code winnerPoints}; senão {@code wrongPoints}.</li>
     *   <li><b>Prorrogação</b> (só quando a partida foi de fato à prorrogação — KO jogo único):
     *       placar exato da prorrogação → {@code extraTimeExactScorePoints}; senão acerto de quem
     *       vence a prorrogação → {@code extraTimeWinnerPoints}; senão 0. Só conta se o palpiteiro
     *       informou o placar da prorrogação.</li>
     *   <li><b>Pênaltis</b> (só quando o confronto foi decidido nos pênaltis): acertar quem passa →
     *       {@code penaltyWinnerPoints}; senão 0.</li>
     * </ol>
     * Ex.: acertar o empate no tempo normal + placar exato da prorrogação + quem passa nos pênaltis
     * soma os três valores.
     */
    int scoreFor(Prediction prediction, Match match, TournamentSettings settings) {
        if (match.getStatus() != MatchStatus.COMPLETED
                || match.getHomeScore() == null
                || match.getAwayScore() == null) {
            return 0;
        }
        int total = regularTimeScore(prediction, match, settings);
        total += extraTimeScore(prediction, match, settings);
        total += penaltyScore(prediction, match, settings);
        return total;
    }

    /** Componente do tempo normal: placar exato → vencedor → erro. */
    private int regularTimeScore(Prediction prediction, Match match, TournamentSettings settings) {
        int actualHome = match.getHomeScore();
        int actualAway = match.getAwayScore();
        int guessedHome = prediction.getHomeScore();
        int guessedAway = prediction.getAwayScore();
        if (guessedHome == actualHome && guessedAway == actualAway) {
            return settings.getExactScorePoints();
        }
        if (Integer.compare(guessedHome, guessedAway) == Integer.compare(actualHome, actualAway)) {
            return settings.getWinnerPoints();
        }
        return settings.getWrongPoints();
    }

    /**
     * Componente da prorrogação, somado ao tempo normal. Só quando a partida foi à prorrogação
     * (placar de ET real presente) e o palpiteiro informou o placar da prorrogação. Placar exato →
     * {@code extraTimeExactScorePoints}; só o vencedor → {@code extraTimeWinnerPoints}; senão 0.
     */
    private int extraTimeScore(Prediction prediction, Match match, TournamentSettings settings) {
        if (match.getHomeExtraTimeScore() == null || match.getAwayExtraTimeScore() == null
                || prediction.getHomeExtraTimeScore() == null
                || prediction.getAwayExtraTimeScore() == null) {
            return 0;
        }
        int actualHome = match.getHomeExtraTimeScore();
        int actualAway = match.getAwayExtraTimeScore();
        int guessedHome = prediction.getHomeExtraTimeScore();
        int guessedAway = prediction.getAwayExtraTimeScore();
        if (guessedHome == actualHome && guessedAway == actualAway) {
            return settings.getExtraTimeExactScorePoints();
        }
        if (Integer.compare(guessedHome, guessedAway) == Integer.compare(actualHome, actualAway)) {
            return settings.getExtraTimeWinnerPoints();
        }
        return 0;
    }

    /**
     * Componente dos pênaltis, somado aos demais. Só quando o confronto foi decidido nos pênaltis
     * e o palpiteiro indicou quem passa. Acertar quem se classificou → {@code penaltyWinnerPoints}.
     */
    private int penaltyScore(Prediction prediction, Match match, TournamentSettings settings) {
        Team progressor = actualPenaltyProgressor(match);
        if (progressor == null || prediction.getPenaltyWinner() == null) {
            return 0;
        }
        Team guessedTeam = prediction.getPenaltyWinner() == MatchSide.HOME
                ? match.getHomeTeam()
                : match.getAwayTeam();
        return guessedTeam.getId().equals(progressor.getId()) ? settings.getPenaltyWinnerPoints() : 0;
    }

    /**
     * Time que efetivamente se classificou nos pênaltis neste confronto, ou {@code null} se a
     * partida/confronto não foi decidida nos pênaltis (resultado no tempo normal / agregado, ou
     * pênaltis ainda não lançados). Em jogo único usa o placar + pênaltis da própria partida; em
     * ida-e-volta usa o agregado do confronto ({@link TieAggregateCalculator}).
     */
    private Team actualPenaltyProgressor(Match match) {
        TournamentPhase phase = match.getPhase();
        if (phase.getPhaseType() != TournamentPhaseType.KNOCKOUT) {
            return null;
        }
        if (legModeResolver.effectiveLegMode(match) == MatchLegMode.SINGLE) {
            if (match.getHomePenalties() == null || match.getAwayPenalties() == null) {
                return null;
            }
            // Placar decisivo: prorrogação se houve, senão o tempo normal. Pênaltis só valem se esse
            // placar terminou empatado (senão o confronto foi decidido antes da disputa).
            int decisiveHome = match.getHomeExtraTimeScore() != null
                    ? match.getHomeExtraTimeScore() : match.getHomeScore();
            int decisiveAway = match.getAwayExtraTimeScore() != null
                    ? match.getAwayExtraTimeScore() : match.getAwayScore();
            if (decisiveHome != decisiveAway) {
                return null; // decidido no tempo normal/prorrogação — pênaltis não valem
            }
            return match.getHomePenalties() > match.getAwayPenalties()
                    ? match.getHomeTeam()
                    : match.getAwayTeam();
        }
        // TWO_LEGGED: só o agregado decide; winner != null apenas em empate resolvido nos pênaltis.
        var aggregate = tieCalculator.compute(matchRepository.findAllByTieId(match.getTieId()));
        if (aggregate.homeAggregate() != aggregate.awayAggregate()) {
            return null; // confronto decidido no agregado, sem pênaltis
        }
        return aggregate.winner();
    }

    /**
     * Valida a cascata do palpite de mata-mata.
     *
     * <p><b>Jogo único (KNOCKOUT SINGLE)</b> — cascata obrigatória:
     * <ul>
     *   <li>Palpite de empate no tempo normal ⇒ é obrigatório o placar da prorrogação
     *       ({@code >=} tempo normal por time, ambos juntos).</li>
     *   <li>Se a prorrogação palpitada também for empate ⇒ é obrigatório {@code penaltyWinner}.</li>
     *   <li>Se a prorrogação palpitada tiver vencedor ⇒ {@code penaltyWinner} não é aceito.</li>
     *   <li>Palpite não-empate no tempo normal ⇒ prorrogação e {@code penaltyWinner} não são aceitos.</li>
     * </ul>
     *
     * <p><b>Demais confrontos</b> — a prorrogação não se aplica. {@code penaltyWinner} segue a regra
     * do agregado: aceito/obrigatório apenas na perna de volta de ida-e-volta empatada no agregado.
     */
    private void validatePredictionCascade(Match match, PlacePredictionRequest request) {
        TournamentPhase phase = match.getPhase();
        // Modo efetivo do confronto: a rodada final de um KO pode ter modo próprio (finalLegMode).
        boolean singleLegKo = phase.getPhaseType() == TournamentPhaseType.KNOCKOUT
                && legModeResolver.effectiveLegMode(match) == MatchLegMode.SINGLE;

        Integer etHome = request.homeExtraTimeScore();
        Integer etAway = request.awayExtraTimeScore();
        boolean hasExtraTime = etHome != null || etAway != null;

        if (!singleLegKo) {
            if (hasExtraTime) {
                throw badRequest("extraTimeScore only applies to a single-leg knockout match");
            }
            validateTwoLeggedPenaltyWinner(match, request);
            return;
        }

        boolean predictedRegularDraw = request.homeScore().equals(request.awayScore());
        if (!predictedRegularDraw) {
            if (hasExtraTime) {
                throw badRequest("extraTimeScore only applies when you predict a draw in regular time");
            }
            if (request.penaltyWinner() != null) {
                throw badRequest("penaltyWinner only applies when your prediction ends level");
            }
            return;
        }

        // Empate no tempo normal: prorrogação é obrigatória e cumulativa.
        if (etHome == null || etAway == null) {
            throw badRequest("extra-time score is required when you predict a draw in a single-leg knockout");
        }
        if (etHome < request.homeScore() || etAway < request.awayScore()) {
            throw badRequest("extra-time score cannot be lower than your regular-time score");
        }

        boolean predictedExtraTimeDraw = etHome.equals(etAway);
        if (predictedExtraTimeDraw) {
            if (request.penaltyWinner() == null) {
                throw badRequest("penaltyWinner is required when your extra-time prediction is a draw");
            }
        } else if (request.penaltyWinner() != null) {
            throw badRequest("penaltyWinner only applies when your extra-time prediction is a draw");
        }
    }

    /**
     * Regra de {@code penaltyWinner} para confrontos de ida-e-volta: aceito/obrigatório só na perna
     * de volta empatada no agregado (gols reais das pernas anteriores + placar palpitado desta).
     */
    private void validateTwoLeggedPenaltyWinner(Match match, PlacePredictionRequest request) {
        List<Match> legs = matchRepository.findAllByTieId(match.getTieId());
        boolean eligible = penaltyHelper.isEligible(match, legs);
        int[] aggBefore = penaltyHelper.aggregateBefore(match, legs);
        boolean predictedDraw =
                (aggBefore[0] + request.homeScore()) == (aggBefore[1] + request.awayScore());
        if (request.penaltyWinner() != null) {
            if (!eligible) {
                throw badRequest(
                        "penaltyWinner only applies to a single-leg knockout match or the second "
                                + "leg of a two-legged tie");
            }
            if (!predictedDraw) {
                throw badRequest("penaltyWinner only applies when your prediction ends the tie in a draw");
            }
        } else if (eligible && predictedDraw) {
            throw badRequest("penaltyWinner is required when your prediction ends the tie in a draw");
        }
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST);
    }

    private Tournament loadTournament(UUID tournamentPublicId) {
        return tournamentRepository.findByPublicIdAndActiveTrue(tournamentPublicId)
                .orElseThrow(TournamentNotFoundException::new);
    }

    private Tournament loadOwnedTournament(UUID ownerPublicId, UUID tournamentPublicId) {
        Tournament tournament = loadTournament(tournamentPublicId);
        if (!tournament.getOwner().getPublicId().equals(ownerPublicId)) {
            throw new NotTournamentOwnerException();
        }
        return tournament;
    }

    private void ensureTournamentInProgress(Tournament tournament) {
        if (tournament.getStatus() != TournamentStatus.IN_PROGRESS) {
            throw new PredictionLockedException(
                    "Predictions are only accepted while tournament is IN_PROGRESS"
            );
        }
    }

    private void ensureActiveMember(Tournament tournament, UUID userPublicId) {
        TournamentMember member = memberRepository
                .findByTournamentPublicIdAndUserPublicId(tournament.getPublicId(), userPublicId)
                .orElseThrow(NotTournamentMemberException::new);
        if (member.getStatus() != TournamentMemberStatus.ACTIVE) {
            throw new NotTournamentMemberException();
        }
    }

    private Match loadMatchInTournament(Tournament tournament, UUID matchPublicId) {
        Match match = matchRepository.findByPublicId(matchPublicId)
                .orElseThrow(MatchNotFoundException::new);
        if (!match.getPhase().getTournament().getId().equals(tournament.getId())) {
            throw new MatchNotFoundException();
        }
        return match;
    }
}
