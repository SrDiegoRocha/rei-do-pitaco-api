package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.request.CreatePhaseRequest;
import com.example.reidopitaco.dto.request.MovePhaseRequest;
import com.example.reidopitaco.dto.request.UpdatePhaseRequest;
import com.example.reidopitaco.dto.response.PhaseResponse;
import com.example.reidopitaco.entity.PhaseTeam;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.entity.TournamentTeam;
import com.example.reidopitaco.enums.BracketMode;
import com.example.reidopitaco.enums.MatchGenerationMode;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.enums.TournamentStatus;
import com.example.reidopitaco.exception.NotTournamentOwnerException;
import com.example.reidopitaco.exception.PhaseHasMatchesException;
import com.example.reidopitaco.exception.PhaseNotFoundException;
import com.example.reidopitaco.exception.PhaseStructureLockedException;
import com.example.reidopitaco.exception.TournamentNotFoundException;
import com.example.reidopitaco.mapper.PhaseMapper;
import com.example.reidopitaco.repository.MatchRepository;
import com.example.reidopitaco.repository.PhaseGroupRepository;
import com.example.reidopitaco.repository.PhaseTeamRepository;
import com.example.reidopitaco.repository.TournamentPhaseRepository;
import com.example.reidopitaco.repository.TournamentRepository;
import com.example.reidopitaco.repository.TournamentTeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class PhaseService {

    private final TournamentRepository tournamentRepository;
    private final TournamentPhaseRepository phaseRepository;
    private final PhaseGroupRepository phaseGroupRepository;
    private final PhaseTeamRepository phaseTeamRepository;
    private final TournamentTeamRepository tournamentTeamRepository;
    private final MatchRepository matchRepository;
    private final PhaseMapper phaseMapper;

    public PhaseService(
            TournamentRepository tournamentRepository,
            TournamentPhaseRepository phaseRepository,
            PhaseGroupRepository phaseGroupRepository,
            PhaseTeamRepository phaseTeamRepository,
            TournamentTeamRepository tournamentTeamRepository,
            MatchRepository matchRepository,
            PhaseMapper phaseMapper
    ) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.phaseGroupRepository = phaseGroupRepository;
        this.phaseTeamRepository = phaseTeamRepository;
        this.tournamentTeamRepository = tournamentTeamRepository;
        this.matchRepository = matchRepository;
        this.phaseMapper = phaseMapper;
    }

    @Transactional
    public PhaseResponse create(UUID ownerPublicId, UUID tournamentPublicId, CreatePhaseRequest request) {
        Tournament tournament = loadOwnedEditable(ownerPublicId, tournamentPublicId);

        int position = (int) phaseRepository.countByTournamentId(tournament.getId());

        TournamentPhase phase = TournamentPhase.builder()
                .tournament(tournament)
                .name(request.name().trim())
                .position(position)
                .phaseType(request.phaseType())
                .matchLegMode(request.matchLegMode())
                .matchGenerationMode(request.matchGenerationMode())
                .playsInsideGroupOnly(
                        request.phaseType() == TournamentPhaseType.GROUPS
                                ? request.playsInsideGroupOnly()
                                : null
                )
                .hasThirdPlace(
                        request.phaseType() == TournamentPhaseType.KNOCKOUT
                                && Boolean.TRUE.equals(request.hasThirdPlace())
                )
                .finalLegMode(
                        request.phaseType() == TournamentPhaseType.KNOCKOUT
                                ? request.finalLegMode()
                                : null
                )
                .bracketMode(resolveBracketMode(
                        request.phaseType(), request.matchGenerationMode(), request.bracketMode()
                ))
                .build();

        TournamentPhase saved = phaseRepository.save(phase);

        if (position == 0) {
            autoPopulateFromTournamentTeams(saved);
        }

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<PhaseResponse> list(UUID ownerPublicId, UUID tournamentPublicId) {
        loadOwnedOrReader(ownerPublicId, tournamentPublicId);
        return phaseRepository.findAllByTournamentPublicIdOrderByPositionAsc(tournamentPublicId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PhaseResponse getById(UUID ownerPublicId, UUID tournamentPublicId, UUID phasePublicId) {
        loadOwnedOrReader(ownerPublicId, tournamentPublicId);
        TournamentPhase phase = phaseRepository
                .findByPublicIdAndTournamentPublicId(phasePublicId, tournamentPublicId)
                .orElseThrow(PhaseNotFoundException::new);
        return toResponse(phase);
    }

    @Transactional
    public PhaseResponse update(
            UUID ownerPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId,
            UpdatePhaseRequest request
    ) {
        Tournament tournament = loadOwnedEditable(ownerPublicId, tournamentPublicId);
        TournamentPhase phase = loadPhase(tournament, phasePublicId);

        phase.setName(request.name().trim());
        phase.setPhaseType(request.phaseType());
        phase.setMatchLegMode(request.matchLegMode());
        phase.setMatchGenerationMode(request.matchGenerationMode());
        phase.setPlaysInsideGroupOnly(
                request.phaseType() == TournamentPhaseType.GROUPS
                        ? request.playsInsideGroupOnly()
                        : null
        );
        phase.setHasThirdPlace(
                request.phaseType() == TournamentPhaseType.KNOCKOUT
                        && Boolean.TRUE.equals(request.hasThirdPlace())
        );
        phase.setFinalLegMode(
                request.phaseType() == TournamentPhaseType.KNOCKOUT
                        ? request.finalLegMode()
                        : null
        );
        phase.setBracketMode(resolveBracketMode(
                request.phaseType(), request.matchGenerationMode(), request.bracketMode()
        ));

        return toResponse(phaseRepository.saveAndFlush(phase));
    }

    @Transactional
    public PhaseResponse move(
            UUID ownerPublicId,
            UUID tournamentPublicId,
            UUID phasePublicId,
            MovePhaseRequest request
    ) {
        Tournament tournament = loadOwnedEditable(ownerPublicId, tournamentPublicId);
        TournamentPhase phase = loadPhase(tournament, phasePublicId);

        long total = phaseRepository.countByTournamentId(tournament.getId());
        int newPosition = Math.min(request.position(), (int) total - 1);
        int oldPosition = phase.getPosition();

        if (newPosition == oldPosition) {
            return toResponse(phase);
        }

        phase.setPosition(-1);
        phaseRepository.flush();

        if (newPosition < oldPosition) {
            phaseRepository.shiftPositions(tournament.getId(), newPosition, oldPosition - 1, 1);
        } else {
            phaseRepository.shiftPositions(tournament.getId(), oldPosition + 1, newPosition, -1);
        }
        phaseRepository.flush();

        phase.setPosition(newPosition);
        return toResponse(phaseRepository.saveAndFlush(phase));
    }

    @Transactional
    public void delete(UUID ownerPublicId, UUID tournamentPublicId, UUID phasePublicId) {
        Tournament tournament = loadOwnedEditable(ownerPublicId, tournamentPublicId);
        TournamentPhase phase = loadPhase(tournament, phasePublicId);
        if (matchRepository.countByPhaseId(phase.getId()) > 0) {
            throw new PhaseHasMatchesException("phase");
        }
        int removedPosition = phase.getPosition();

        phaseRepository.delete(phase);
        phaseRepository.flush();

        long total = phaseRepository.countByTournamentId(tournament.getId());
        if (removedPosition < total) {
            phaseRepository.shiftPositions(tournament.getId(), removedPosition + 1, (int) total, -1);
        }
    }

    Tournament loadOwnedEditable(UUID ownerPublicId, UUID tournamentPublicId) {
        Tournament tournament = tournamentRepository.findByPublicIdAndActiveTrue(tournamentPublicId)
                .orElseThrow(TournamentNotFoundException::new);
        if (!tournament.getOwner().getPublicId().equals(ownerPublicId)) {
            throw new NotTournamentOwnerException();
        }
        TournamentStatus status = tournament.getStatus();
        if (status == TournamentStatus.IN_PROGRESS || status == TournamentStatus.FINISHED) {
            throw new PhaseStructureLockedException(status);
        }
        return tournament;
    }

    Tournament loadOwnedNotFinished(UUID ownerPublicId, UUID tournamentPublicId) {
        Tournament tournament = tournamentRepository.findByPublicIdAndActiveTrue(tournamentPublicId)
                .orElseThrow(TournamentNotFoundException::new);
        if (!tournament.getOwner().getPublicId().equals(ownerPublicId)) {
            throw new NotTournamentOwnerException();
        }
        if (tournament.getStatus() == TournamentStatus.FINISHED) {
            throw new PhaseStructureLockedException(tournament.getStatus());
        }
        return tournament;
    }

    Tournament loadOwnedOrReader(UUID requesterPublicId, UUID tournamentPublicId) {
        return tournamentRepository.findByPublicIdAndActiveTrue(tournamentPublicId)
                .orElseThrow(TournamentNotFoundException::new);
    }

    TournamentPhase loadPhase(Tournament tournament, UUID phasePublicId) {
        return phaseRepository
                .findByPublicIdAndTournamentPublicId(phasePublicId, tournament.getPublicId())
                .orElseThrow(PhaseNotFoundException::new);
    }

    /**
     * Chaveamento só existe em KNOCKOUT ({@code null} nas demais). Quando o cliente não manda o
     * campo, o default preserva o comportamento histórico de cada modo de geração: AUTOMATIC
     * sempre emparelhou em ordem canônica (chaveamento fixo); MANUAL era livre (sem chaveamento).
     */
    private BracketMode resolveBracketMode(
            TournamentPhaseType phaseType,
            MatchGenerationMode generationMode,
            BracketMode requested
    ) {
        if (phaseType != TournamentPhaseType.KNOCKOUT) {
            return null;
        }
        if (requested != null) {
            return requested;
        }
        return generationMode == MatchGenerationMode.MANUAL
                ? BracketMode.REDRAW_EACH_ROUND
                : BracketMode.FIXED_BRACKET;
    }

    private void autoPopulateFromTournamentTeams(TournamentPhase phase) {
        List<TournamentTeam> links = tournamentTeamRepository
                .findAllByTournamentPublicId(phase.getTournament().getPublicId());
        if (links.isEmpty()) {
            return;
        }
        List<PhaseTeam> phaseTeams = new ArrayList<>(links.size());
        for (TournamentTeam link : links) {
            phaseTeams.add(PhaseTeam.builder()
                    .phase(phase)
                    .team(link.getTeam())
                    .build());
        }
        phaseTeamRepository.saveAll(phaseTeams);
    }

    PhaseResponse toResponse(TournamentPhase phase) {
        long groupCount = phaseGroupRepository.countByPhaseId(phase.getId());
        long teamCount = phaseTeamRepository.countByPhaseId(phase.getId());
        return phaseMapper.toResponse(phase, groupCount, teamCount);
    }
}
