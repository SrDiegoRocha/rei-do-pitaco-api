package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.request.CreateMatchRequest;
import com.example.reidopitaco.dto.request.SetMatchResultRequest;
import com.example.reidopitaco.dto.request.UpdateMatchRequest;
import com.example.reidopitaco.dto.response.MatchLocationResponse;
import com.example.reidopitaco.dto.response.MatchResponse;
import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.PhaseGroup;
import com.example.reidopitaco.entity.PhaseTeam;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.enums.MatchLegMode;
import com.example.reidopitaco.enums.MatchStatus;
import com.example.reidopitaco.enums.MatchType;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.enums.TournamentStatus;
import com.example.reidopitaco.exception.InvalidMatchException;
import com.example.reidopitaco.exception.MatchNotFoundException;
import com.example.reidopitaco.exception.MatchResultNotAllowedException;
import com.example.reidopitaco.exception.NotTournamentOwnerException;
import com.example.reidopitaco.exception.PhaseGroupNotFoundException;
import com.example.reidopitaco.exception.TournamentNotEditableException;
import com.example.reidopitaco.exception.TournamentNotFoundException;
import com.example.reidopitaco.mapper.MatchMapper;
import com.example.reidopitaco.repository.MatchRepository;
import com.example.reidopitaco.repository.PhaseGroupRepository;
import com.example.reidopitaco.repository.PhaseTeamRepository;
import com.example.reidopitaco.repository.TournamentPhaseRepository;
import com.example.reidopitaco.repository.TournamentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class MatchService {

    private final TournamentRepository tournamentRepository;
    private final TournamentPhaseRepository phaseRepository;
    private final PhaseGroupRepository groupRepository;
    private final PhaseTeamRepository phaseTeamRepository;
    private final MatchRepository matchRepository;
    private final MatchMapper mapper;
    private final PredictionService predictionService;
    private final PhasePredictionScoringService phasePredictionScoringService;
    private final TournamentAccessGuard accessGuard;
    private final MatchNotificationService matchNotificationService;

    public MatchService(
            TournamentRepository tournamentRepository,
            TournamentPhaseRepository phaseRepository,
            PhaseGroupRepository groupRepository,
            PhaseTeamRepository phaseTeamRepository,
            MatchRepository matchRepository,
            MatchMapper mapper,
            PredictionService predictionService,
            PhasePredictionScoringService phasePredictionScoringService,
            TournamentAccessGuard accessGuard,
            MatchNotificationService matchNotificationService
    ) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.groupRepository = groupRepository;
        this.phaseTeamRepository = phaseTeamRepository;
        this.matchRepository = matchRepository;
        this.mapper = mapper;
        this.predictionService = predictionService;
        this.phasePredictionScoringService = phasePredictionScoringService;
        this.accessGuard = accessGuard;
        this.matchNotificationService = matchNotificationService;
    }

    @Transactional
    public MatchResponse create(
            UUID ownerPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId,
            CreateMatchRequest request
    ) {
        Tournament tournament = loadOwnedActive(ownerPublicId, tournamentPublicId);
        ensureNotFinished(tournament);
        TournamentPhase phase = loadPhase(tournament, phasePublicId);

        if (request.homeTeamId().equals(request.awayTeamId())) {
            throw new InvalidMatchException("A team cannot play against itself");
        }

        PhaseTeam home = requireTeamInPhase(phase, request.homeTeamId(), "home team");
        PhaseTeam away = requireTeamInPhase(phase, request.awayTeamId(), "away team");

        PhaseGroup group = resolveGroup(phase, request.groupId(), home, away);

        ensureTeamFreeInRound(phase, request.round(), home.getTeam().getId(), null);
        ensureTeamFreeInRound(phase, request.round(), away.getTeam().getId(), null);

        UUID tieId = request.tieId() != null ? request.tieId() : UUID.randomUUID();
        if (request.tieId() != null) {
            validateTieConsistency(request.tieId(), phase, home.getTeam(), away.getTeam(), request.round());
        }

        Match match = Match.builder()
                .phase(phase)
                .group(group)
                .round(request.round())
                .tieId(tieId)
                .homeTeam(home.getTeam())
                .awayTeam(away.getTeam())
                .scheduledAt(request.scheduledAt())
                .status(MatchStatus.SCHEDULED)
                .matchType(resolveMatchType(phase, request.matchType()))
                .build();
        return mapper.toResponse(matchRepository.save(match));
    }

    @Transactional(readOnly = true)
    public List<MatchResponse> list(
            UUID requesterPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId,
            Integer round,
            UUID groupId
    ) {
        accessGuard.requireViewable(requesterPublicId, tournamentPublicId);
        List<Match> matches;
        if (round != null) {
            matches = matchRepository.findAllByPhasePublicIdAndRound(phasePublicId, round);
        } else if (groupId != null) {
            matches = matchRepository.findAllByPhasePublicIdAndGroupPublicId(phasePublicId, groupId);
        } else {
            matches = matchRepository.findAllByPhasePublicId(phasePublicId);
        }
        return matches.stream().map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<MatchResponse> listByTournament(UUID requesterPublicId, UUID tournamentPublicId) {
        accessGuard.requireViewable(requesterPublicId, tournamentPublicId);
        return matchRepository.findAllByTournamentPublicIdOrdered(tournamentPublicId)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public MatchResponse getById(
            UUID requesterPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId,
            UUID matchPublicId
    ) {
        accessGuard.requireViewable(requesterPublicId, tournamentPublicId);
        Match match = matchRepository.findByPublicIdAndPhasePublicId(matchPublicId, phasePublicId)
                .orElseThrow(MatchNotFoundException::new);
        return mapper.toResponse(match);
    }

    /**
     * Resolve um id de partida para sua localização (torneio + fase), usado
     * pelos links curtos /m/{matchId}. Só exige autenticação — a checagem de
     * acesso/participação acontece depois, ao abrir o detalhe da partida.
     */
    @Transactional(readOnly = true)
    public MatchLocationResponse locate(UUID matchPublicId) {
        Match match = matchRepository.findByPublicIdWithLocation(matchPublicId)
                .orElseThrow(MatchNotFoundException::new);
        TournamentPhase phase = match.getPhase();
        return new MatchLocationResponse(
                phase.getTournament().getPublicId(),
                phase.getPublicId(),
                match.getPublicId()
        );
    }

    @Transactional
    public MatchResponse update(
            UUID ownerPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId,
            UUID matchPublicId,
            UpdateMatchRequest request
    ) {
        Tournament tournament = loadOwnedActive(ownerPublicId, tournamentPublicId);
        ensureNotFinished(tournament);
        TournamentPhase phase = loadPhase(tournament, phasePublicId);
        Match match = loadMatch(phase, matchPublicId);

        if (match.getStatus() == MatchStatus.COMPLETED) {
            throw new InvalidMatchException("Cannot edit match scheduling after result is set; clear result first");
        }

        if (request.homeTeamId().equals(request.awayTeamId())) {
            throw new InvalidMatchException("A team cannot play against itself");
        }

        PhaseTeam home = requireTeamInPhase(phase, request.homeTeamId(), "home team");
        PhaseTeam away = requireTeamInPhase(phase, request.awayTeamId(), "away team");
        PhaseGroup group = resolveGroup(phase, request.groupId(), home, away);

        ensureTeamFreeInRound(phase, request.round(), home.getTeam().getId(), match.getId());
        ensureTeamFreeInRound(phase, request.round(), away.getTeam().getId(), match.getId());

        var previousScheduledAt = match.getScheduledAt();

        match.setRound(request.round());
        match.setGroup(group);
        match.setHomeTeam(home.getTeam());
        match.setAwayTeam(away.getTeam());
        match.setScheduledAt(request.scheduledAt());
        match.setMatchType(resolveMatchType(phase, request.matchType()));

        // Remarcar a partida reabre os lembretes: zera as flags de 24h/4h/1h para
        // que disparem de novo conforme o novo horário. A flag de resultado fica.
        if (!java.util.Objects.equals(previousScheduledAt, request.scheduledAt())) {
            match.setNotified24h(false);
            match.setNotified4h(false);
            match.setNotified1h(false);
        }

        return mapper.toResponse(matchRepository.saveAndFlush(match));
    }

    @Transactional
    public MatchResponse setResult(
            UUID ownerPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId,
            UUID matchPublicId,
            SetMatchResultRequest request
    ) {
        Tournament tournament = loadOwnedActive(ownerPublicId, tournamentPublicId);
        if (tournament.getStatus() != TournamentStatus.IN_PROGRESS) {
            throw new MatchResultNotAllowedException(
                    "Results can only be set while tournament is IN_PROGRESS"
            );
        }
        TournamentPhase phase = loadPhase(tournament, phasePublicId);
        Match match = loadMatch(phase, matchPublicId);

        if (match.getStatus() == MatchStatus.CANCELLED) {
            throw new MatchResultNotAllowedException("Cannot set result on a cancelled match");
        }
        // Com horário marcado, o resultado só pode entrar após o deadline de palpites (scheduledAt).
        // Sem horário marcado, pode lançar a qualquer momento — lançar o resultado é o que trava os palpites.
        if (match.getScheduledAt() != null && java.time.Instant.now().isBefore(match.getScheduledAt())) {
            throw new MatchResultNotAllowedException(
                    "Results can only be set after the prediction deadline (scheduledAt)"
            );
        }

        match.setHomeScore(request.homeScore());
        match.setAwayScore(request.awayScore());
        applyExtraTime(phase, match, request);
        applyPenalties(phase, match, request);
        match.setStatus(MatchStatus.COMPLETED);
        Match saved = matchRepository.saveAndFlush(match);
        predictionService.recalculatePointsFor(saved);
        // Resultado novo/editado muda a classificação/bracket — repontua o Pick'em da fase.
        phasePredictionScoringService.recalculateForPhase(saved.getPhase());
        matchNotificationService.notifyResultAvailable(saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public MatchResponse cancel(
            UUID ownerPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId,
            UUID matchPublicId
    ) {
        Tournament tournament = loadOwnedActive(ownerPublicId, tournamentPublicId);
        ensureNotFinished(tournament);
        TournamentPhase phase = loadPhase(tournament, phasePublicId);
        Match match = loadMatch(phase, matchPublicId);
        match.setStatus(MatchStatus.CANCELLED);
        match.setHomeScore(null);
        match.setAwayScore(null);
        match.setHomeExtraTimeScore(null);
        match.setAwayExtraTimeScore(null);
        match.setHomePenalties(null);
        match.setAwayPenalties(null);
        predictionService.zeroPointsFor(match);
        Match saved = matchRepository.saveAndFlush(match);
        // Cancelamento tira a partida da classificação/bracket — repontua o Pick'em da fase.
        phasePredictionScoringService.recalculateForPhase(saved.getPhase());
        return mapper.toResponse(saved);
    }

    @Transactional
    public void delete(
            UUID ownerPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId,
            UUID matchPublicId
    ) {
        Tournament tournament = loadOwnedActive(ownerPublicId, tournamentPublicId);
        ensureNotFinished(tournament);
        TournamentPhase phase = loadPhase(tournament, phasePublicId);
        Match match = loadMatch(phase, matchPublicId);
        matchRepository.delete(match);
    }

    private Tournament loadOwnedActive(UUID ownerPublicId, UUID tournamentPublicId) {
        Tournament tournament = tournamentRepository.findByPublicIdAndActiveTrue(tournamentPublicId)
                .orElseThrow(TournamentNotFoundException::new);
        if (!tournament.getOwner().getPublicId().equals(ownerPublicId)) {
            throw new NotTournamentOwnerException();
        }
        return tournament;
    }

    private void ensureNotFinished(Tournament tournament) {
        if (tournament.getStatus() == TournamentStatus.FINISHED) {
            throw new TournamentNotEditableException(
                    tournament.getStatus(),
                    "matches cannot be edited"
            );
        }
    }

    private TournamentPhase loadPhase(Tournament tournament, UUID phasePublicId) {
        return phaseRepository
                .findByPublicIdAndTournamentPublicId(phasePublicId, tournament.getPublicId())
                .orElseThrow(() -> new com.example.reidopitaco.exception.PhaseNotFoundException());
    }

    private Match loadMatch(TournamentPhase phase, UUID matchPublicId) {
        return matchRepository.findByPublicIdAndPhasePublicId(matchPublicId, phase.getPublicId())
                .orElseThrow(MatchNotFoundException::new);
    }

    private PhaseTeam requireTeamInPhase(TournamentPhase phase, UUID teamPublicId, String label) {
        return phaseTeamRepository
                .findByPhasePublicIdAndTeamPublicId(phase.getPublicId(), teamPublicId)
                .orElseThrow(() -> new InvalidMatchException(label + " is not registered in this phase"));
    }

    private PhaseGroup resolveGroup(
            TournamentPhase phase,
            UUID groupPublicId,
            PhaseTeam home,
            PhaseTeam away
    ) {
        if (phase.getPhaseType() == TournamentPhaseType.GROUPS) {
            if (groupPublicId == null) {
                throw new InvalidMatchException("groupId is required for GROUPS phase");
            }
            PhaseGroup group = groupRepository
                    .findByPublicIdAndPhasePublicId(groupPublicId, phase.getPublicId())
                    .orElseThrow(PhaseGroupNotFoundException::new);
            if (home.getGroup() == null || !home.getGroup().getId().equals(group.getId())) {
                throw new InvalidMatchException("home team is not in the specified group");
            }
            if (away.getGroup() == null || !away.getGroup().getId().equals(group.getId())) {
                throw new InvalidMatchException("away team is not in the specified group");
            }
            return group;
        }
        if (groupPublicId != null) {
            throw new InvalidMatchException("groupId only applies to GROUPS phase");
        }
        return null;
    }

    private MatchType resolveMatchType(TournamentPhase phase, MatchType requested) {
        MatchType type = requested != null ? requested : MatchType.REGULAR;
        if (type == MatchType.THIRD_PLACE && phase.getPhaseType() != TournamentPhaseType.KNOCKOUT) {
            throw new InvalidMatchException("THIRD_PLACE matches are only allowed in KNOCKOUT phases");
        }
        return type;
    }

    /**
     * Prorrogação do resultado real. Só em KNOCKOUT de jogo único (SINGLE) empatado no tempo normal.
     * O placar é cumulativo (inclui os gols do tempo normal), então nunca pode ser menor que o do
     * tempo normal por time. Ambos os campos vêm juntos; ausentes zeram a prorrogação da partida.
     */
    private void applyExtraTime(TournamentPhase phase, Match match, SetMatchResultRequest request) {
        Integer he = request.homeExtraTimeScore();
        Integer ae = request.awayExtraTimeScore();
        if (he == null && ae == null) {
            match.setHomeExtraTimeScore(null);
            match.setAwayExtraTimeScore(null);
            return;
        }
        if (he == null || ae == null) {
            throw new InvalidMatchException("Both extra-time scores must be provided together");
        }
        if (phase.getPhaseType() != TournamentPhaseType.KNOCKOUT
                || phase.getMatchLegMode() != MatchLegMode.SINGLE) {
            throw new InvalidMatchException("Extra time only applies to single-leg KNOCKOUT matches");
        }
        if (!match.getHomeScore().equals(match.getAwayScore())) {
            throw new InvalidMatchException("Extra time only applies when regular time ended in a draw");
        }
        if (he < match.getHomeScore() || ae < match.getAwayScore()) {
            throw new InvalidMatchException("Extra-time score cannot be lower than the regular-time score");
        }
        match.setHomeExtraTimeScore(he);
        match.setAwayExtraTimeScore(ae);
    }

    private void applyPenalties(TournamentPhase phase, Match match, SetMatchResultRequest request) {
        Integer hp = request.homePenalties();
        Integer ap = request.awayPenalties();
        if (hp == null && ap == null) {
            match.setHomePenalties(null);
            match.setAwayPenalties(null);
            return;
        }
        if (hp == null || ap == null) {
            throw new InvalidMatchException("Both penalty scores must be provided together");
        }
        if (phase.getPhaseType() != TournamentPhaseType.KNOCKOUT) {
            throw new InvalidMatchException("Penalties only apply to KNOCKOUT phases");
        }
        if (hp.equals(ap)) {
            throw new InvalidMatchException("Penalty shootout cannot end in a draw");
        }
        // Jogo único com prorrogação lançada: os pênaltis só fazem sentido se a prorrogação empatou.
        if (phase.getMatchLegMode() == MatchLegMode.SINGLE
                && match.getHomeExtraTimeScore() != null
                && !match.getHomeExtraTimeScore().equals(match.getAwayExtraTimeScore())) {
            throw new InvalidMatchException("Penalties only apply when extra time ended in a draw");
        }
        match.setHomePenalties(hp);
        match.setAwayPenalties(ap);
    }

    private void ensureTeamFreeInRound(
            TournamentPhase phase,
            int round,
            Long teamId,
            Long excludeMatchId
    ) {
        long count = matchRepository.countTeamMatchesInRound(phase.getId(), round, teamId, excludeMatchId);
        if (count > 0) {
            throw new InvalidMatchException("A team cannot play twice in the same round");
        }
    }

    private void validateTieConsistency(
            UUID tieId,
            TournamentPhase phase,
            Team home,
            Team away,
            int round
    ) {
        List<Match> existing = matchRepository.findAllByTieId(tieId);
        for (Match other : existing) {
            if (!other.getPhase().getId().equals(phase.getId())) {
                throw new InvalidMatchException("tieId already belongs to a different phase");
            }
            boolean inverted = other.getHomeTeam().getId().equals(away.getId())
                    && other.getAwayTeam().getId().equals(home.getId());
            if (!inverted) {
                throw new InvalidMatchException("tie legs must have inverted home/away teams");
            }
            if (other.getRound() == round) {
                throw new InvalidMatchException("tie legs must be in different rounds");
            }
        }
        if (existing.size() >= 2) {
            throw new InvalidMatchException("a tie can have at most two legs");
        }
    }
}
