package com.example.futbet.service;

import com.example.futbet.dto.request.CreateMatchRequest;
import com.example.futbet.dto.request.SetMatchResultRequest;
import com.example.futbet.dto.request.UpdateMatchRequest;
import com.example.futbet.dto.response.MatchResponse;
import com.example.futbet.entity.Match;
import com.example.futbet.entity.PhaseGroup;
import com.example.futbet.entity.PhaseTeam;
import com.example.futbet.entity.Team;
import com.example.futbet.entity.Tournament;
import com.example.futbet.entity.TournamentPhase;
import com.example.futbet.enums.MatchStatus;
import com.example.futbet.enums.TournamentPhaseType;
import com.example.futbet.enums.TournamentStatus;
import com.example.futbet.exception.InvalidMatchException;
import com.example.futbet.exception.MatchNotFoundException;
import com.example.futbet.exception.MatchResultNotAllowedException;
import com.example.futbet.exception.NotTournamentOwnerException;
import com.example.futbet.exception.PhaseGroupNotFoundException;
import com.example.futbet.exception.TournamentNotEditableException;
import com.example.futbet.exception.TournamentNotFoundException;
import com.example.futbet.mapper.MatchMapper;
import com.example.futbet.repository.MatchRepository;
import com.example.futbet.repository.PhaseGroupRepository;
import com.example.futbet.repository.PhaseTeamRepository;
import com.example.futbet.repository.TournamentPhaseRepository;
import com.example.futbet.repository.TournamentRepository;
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

    public MatchService(
            TournamentRepository tournamentRepository,
            TournamentPhaseRepository phaseRepository,
            PhaseGroupRepository groupRepository,
            PhaseTeamRepository phaseTeamRepository,
            MatchRepository matchRepository,
            MatchMapper mapper
    ) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.groupRepository = groupRepository;
        this.phaseTeamRepository = phaseTeamRepository;
        this.matchRepository = matchRepository;
        this.mapper = mapper;
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
                .build();
        return mapper.toResponse(matchRepository.save(match));
    }

    @Transactional(readOnly = true)
    public List<MatchResponse> list(
            UUID tournamentPublicId,
            UUID phasePublicId,
            Integer round,
            UUID groupId
    ) {
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
    public MatchResponse getById(UUID tournamentPublicId, UUID phasePublicId, UUID matchPublicId) {
        Match match = matchRepository.findByPublicIdAndPhasePublicId(matchPublicId, phasePublicId)
                .orElseThrow(MatchNotFoundException::new);
        return mapper.toResponse(match);
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

        match.setRound(request.round());
        match.setGroup(group);
        match.setHomeTeam(home.getTeam());
        match.setAwayTeam(away.getTeam());
        match.setScheduledAt(request.scheduledAt());

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

        match.setHomeScore(request.homeScore());
        match.setAwayScore(request.awayScore());
        match.setStatus(MatchStatus.COMPLETED);
        return mapper.toResponse(matchRepository.saveAndFlush(match));
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
        return mapper.toResponse(matchRepository.saveAndFlush(match));
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
                .orElseThrow(() -> new com.example.futbet.exception.PhaseNotFoundException());
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
