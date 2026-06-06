package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.request.PlacePredictionRequest;
import com.example.reidopitaco.dto.response.PredictionResponse;
import com.example.reidopitaco.dto.response.PredictionStatsResponse;
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

    public PredictionService(
            TournamentRepository tournamentRepository,
            TournamentMemberRepository memberRepository,
            MatchRepository matchRepository,
            PredictionRepository predictionRepository,
            UserRepository userRepository,
            PredictionMapper mapper,
            TieAggregateCalculator tieCalculator,
            MatchPenaltyHelper penaltyHelper
    ) {
        this.tournamentRepository = tournamentRepository;
        this.memberRepository = memberRepository;
        this.matchRepository = matchRepository;
        this.predictionRepository = predictionRepository;
        this.userRepository = userRepository;
        this.mapper = mapper;
        this.tieCalculator = tieCalculator;
        this.penaltyHelper = penaltyHelper;
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
        validatePenaltyWinner(match, request);

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
                    .penaltyWinner(request.penaltyWinner())
                    .points(0)
                    .build();
        } else {
            prediction.setHomeScore(request.homeScore());
            prediction.setAwayScore(request.awayScore());
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
            int cmp = Integer.compare(p.getHomeScore(), p.getAwayScore());
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

    int scoreFor(Prediction prediction, Match match, TournamentSettings settings) {
        if (match.getStatus() != MatchStatus.COMPLETED
                || match.getHomeScore() == null
                || match.getAwayScore() == null) {
            return 0;
        }
        int actualHome = match.getHomeScore();
        int actualAway = match.getAwayScore();
        int guessedHome = prediction.getHomeScore();
        int guessedAway = prediction.getAwayScore();
        boolean exactScore = guessedHome == actualHome && guessedAway == actualAway;

        // Confronto decidido nos pênaltis E o palpiteiro indicou quem passa: combina o acerto
        // do placar exato com o acerto de quem se classificou. Ver tabela no # Sistema de palpites.
        Team progressor = actualPenaltyProgressor(match);
        if (progressor != null && prediction.getPenaltyWinner() != null) {
            Team guessedTeam = prediction.getPenaltyWinner() == MatchSide.HOME
                    ? match.getHomeTeam()
                    : match.getAwayTeam();
            boolean advancerCorrect = guessedTeam.getId().equals(progressor.getId());
            int hits = (exactScore ? 1 : 0) + (advancerCorrect ? 1 : 0);
            if (hits == 2) {
                return settings.getExactScorePoints();
            }
            if (hits == 1) {
                return settings.getWinnerPoints();
            }
            return settings.getWrongPoints();
        }

        // Pontuação normal (sem pênaltis em jogo): placar exato → vencedor → erro.
        if (exactScore) {
            return settings.getExactScorePoints();
        }
        int actualOutcome = Integer.compare(actualHome, actualAway);
        int guessedOutcome = Integer.compare(guessedHome, guessedAway);
        if (actualOutcome == guessedOutcome) {
            return settings.getWinnerPoints();
        }
        return settings.getWrongPoints();
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
        if (phase.getMatchLegMode() == MatchLegMode.SINGLE) {
            if (match.getHomePenalties() == null || match.getAwayPenalties() == null) {
                return null;
            }
            if (match.getHomeScore().intValue() != match.getAwayScore().intValue()) {
                return null; // decidido no tempo normal — pênaltis não valem
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
     * Valida o {@code penaltyWinner}: só é aceito em confronto que pode ir aos pênaltis (jogo único
     * de mata-mata ou perna de volta) e quando o palpite leva o confronto a empate. O gatilho do
     * empate é o <b>agregado</b> (gols reais das pernas anteriores + placar palpitado desta), não o
     * placar isolado — em jogo único o agregado anterior é 0x0, então equivale ao placar da partida.
     * Nesses casos elegíveis com empate, é obrigatório.
     */
    private void validatePenaltyWinner(Match match, PlacePredictionRequest request) {
        List<Match> legs = matchRepository.findAllByTieId(match.getTieId());
        boolean eligible = penaltyHelper.isEligible(match, legs);
        int[] aggBefore = penaltyHelper.aggregateBefore(match, legs);
        boolean predictedDraw =
                (aggBefore[0] + request.homeScore()) == (aggBefore[1] + request.awayScore());
        if (request.penaltyWinner() != null) {
            if (!eligible) {
                throw new BusinessException(
                        "penaltyWinner only applies to a single-leg knockout match or the second "
                                + "leg of a two-legged tie",
                        HttpStatus.BAD_REQUEST
                );
            }
            if (!predictedDraw) {
                throw new BusinessException(
                        "penaltyWinner only applies when your prediction ends the tie in a draw",
                        HttpStatus.BAD_REQUEST
                );
            }
        } else if (eligible && predictedDraw) {
            throw new BusinessException(
                    "penaltyWinner is required when your prediction ends the tie in a draw",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private Tournament loadTournament(UUID tournamentPublicId) {
        return tournamentRepository.findByPublicIdAndActiveTrue(tournamentPublicId)
                .orElseThrow(TournamentNotFoundException::new);
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
