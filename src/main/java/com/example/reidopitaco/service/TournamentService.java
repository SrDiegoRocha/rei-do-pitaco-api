package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.request.CreateTournamentRequest;
import com.example.reidopitaco.dto.request.TournamentSettingsPayload;
import com.example.reidopitaco.dto.request.UpdateTournamentRequest;
import com.example.reidopitaco.dto.response.TournamentResponse;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentMember;
import com.example.reidopitaco.entity.TournamentSettings;
import com.example.reidopitaco.entity.TournamentTiebreakCriterion;
import com.example.reidopitaco.entity.User;
import com.example.reidopitaco.enums.TiebreakCriteria;
import com.example.reidopitaco.enums.TournamentMemberRole;
import com.example.reidopitaco.enums.TournamentMemberStatus;
import com.example.reidopitaco.enums.TournamentPrivacy;
import com.example.reidopitaco.enums.TournamentStatus;
import com.example.reidopitaco.exception.BusinessException;
import com.example.reidopitaco.exception.InvalidStatusTransitionException;
import com.example.reidopitaco.exception.NotTournamentOwnerException;
import com.example.reidopitaco.exception.TournamentNotEditableException;
import com.example.reidopitaco.exception.TournamentNotFoundException;
import org.springframework.http.HttpStatus;
import com.example.reidopitaco.mapper.TournamentMapper;
import com.example.reidopitaco.repository.TournamentMemberRepository;
import com.example.reidopitaco.repository.TournamentRepository;
import com.example.reidopitaco.repository.TournamentTeamRepository;
import com.example.reidopitaco.repository.UserRepository;
import com.example.reidopitaco.util.InviteCodeGenerator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class TournamentService {

    private final TournamentRepository tournamentRepository;
    private final TournamentMemberRepository memberRepository;
    private final TournamentTeamRepository teamRepository;
    private final UserRepository userRepository;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final TournamentMapper tournamentMapper;
    private final TournamentAccessGuard accessGuard;

    public TournamentService(
            TournamentRepository tournamentRepository,
            TournamentMemberRepository memberRepository,
            TournamentTeamRepository teamRepository,
            UserRepository userRepository,
            InviteCodeGenerator inviteCodeGenerator,
            TournamentMapper tournamentMapper,
            TournamentAccessGuard accessGuard
    ) {
        this.tournamentRepository = tournamentRepository;
        this.memberRepository = memberRepository;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.inviteCodeGenerator = inviteCodeGenerator;
        this.tournamentMapper = tournamentMapper;
        this.accessGuard = accessGuard;
    }

    @Transactional
    public TournamentResponse create(UUID ownerPublicId, CreateTournamentRequest request) {
        User owner = userRepository.findByPublicId(ownerPublicId)
                .orElseThrow(TournamentNotFoundException::new);

        Tournament tournament = Tournament.builder()
                .owner(owner)
                .name(request.name().trim())
                .description(trimOrNull(request.description()))
                .privacy(request.privacy())
                .status(TournamentStatus.DRAFT)
                .maxParticipants(request.maxParticipants())
                .maxTeams(request.maxTeams())
                .inviteCode(inviteCodeGenerator.generateUnique())
                .tiebreakCriteria(new ArrayList<>())
                .build();

        TournamentSettings settings = buildSettings(tournament, request.settings());
        tournament.setSettings(settings);
        applyTiebreakCriteria(tournament, request.settings().tiebreakCriteria());

        Tournament saved = tournamentRepository.save(tournament);

        TournamentMember ownerMember = TournamentMember.builder()
                .tournament(saved)
                .user(owner)
                .role(TournamentMemberRole.OWNER)
                .status(TournamentMemberStatus.ACTIVE)
                .build();
        memberRepository.save(ownerMember);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public TournamentResponse getById(UUID requesterPublicId, UUID tournamentPublicId) {
        return toResponse(accessGuard.requireViewable(requesterPublicId, tournamentPublicId));
    }

    @Transactional(readOnly = true)
    public Page<TournamentResponse> listMine(UUID ownerPublicId, Pageable pageable) {
        return tournamentRepository.findAllByOwnerPublicIdAndActiveTrue(ownerPublicId, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<TournamentResponse> listPublic(Pageable pageable) {
        return tournamentRepository
                .findAllByPrivacyAndStatusInAndActiveTrue(
                        TournamentPrivacy.PUBLIC,
                        List.of(TournamentStatus.OPEN, TournamentStatus.IN_PROGRESS),
                        pageable
                )
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<TournamentResponse> listJoined(UUID userPublicId, Pageable pageable) {
        return tournamentRepository
                .findJoinedByUser(userPublicId, TournamentMemberStatus.ACTIVE, pageable)
                .map(this::toResponse);
    }

    @Transactional
    public TournamentResponse update(
            UUID ownerPublicId,
            UUID tournamentPublicId,
            UpdateTournamentRequest request
    ) {
        Tournament tournament = loadActiveOwned(tournamentPublicId, ownerPublicId);
        TournamentStatus status = tournament.getStatus();

        if (status == TournamentStatus.FINISHED) {
            throw new TournamentNotEditableException(status, "tournament is finished");
        }

        boolean privacyChanged = tournament.getPrivacy() != request.privacy();
        if (status == TournamentStatus.IN_PROGRESS && privacyChanged) {
            throw new TournamentNotEditableException(status, "privacy is locked once tournament is in progress");
        }

        tournament.setName(request.name().trim());
        tournament.setDescription(trimOrNull(request.description()));
        tournament.setPrivacy(request.privacy());

        validateCapacity(tournament, request.maxParticipants(), request.maxTeams());
        tournament.setMaxParticipants(request.maxParticipants());
        tournament.setMaxTeams(request.maxTeams());

        updateSettings(tournament, request.settings());
        applyTiebreakCriteria(tournament, request.settings().tiebreakCriteria());

        Tournament saved = tournamentRepository.saveAndFlush(tournament);
        return toResponse(saved);
    }

    @Transactional
    public TournamentResponse changeStatus(
            UUID ownerPublicId,
            UUID tournamentPublicId,
            TournamentStatus target
    ) {
        Tournament tournament = loadActiveOwned(tournamentPublicId, ownerPublicId);
        TournamentStatus current = tournament.getStatus();
        if (!current.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(current, target);
        }
        tournament.setStatus(target);
        return toResponse(tournamentRepository.saveAndFlush(tournament));
    }

    @Transactional
    public TournamentResponse regenerateInviteCode(UUID ownerPublicId, UUID tournamentPublicId) {
        Tournament tournament = loadActiveOwned(tournamentPublicId, ownerPublicId);
        tournament.setInviteCode(inviteCodeGenerator.generateUnique());
        return toResponse(tournamentRepository.saveAndFlush(tournament));
    }

    @Transactional
    public void delete(UUID ownerPublicId, UUID tournamentPublicId) {
        Tournament tournament = loadActiveOwned(tournamentPublicId, ownerPublicId);
        tournament.setActive(false);
    }

    Tournament loadActive(UUID tournamentPublicId) {
        return tournamentRepository.findByPublicIdAndActiveTrue(tournamentPublicId)
                .orElseThrow(TournamentNotFoundException::new);
    }

    Tournament loadActiveOwned(UUID tournamentPublicId, UUID ownerPublicId) {
        Tournament tournament = loadActive(tournamentPublicId);
        if (!tournament.getOwner().getPublicId().equals(ownerPublicId)) {
            throw new NotTournamentOwnerException();
        }
        return tournament;
    }

    private TournamentResponse toResponse(Tournament tournament) {
        long memberCount = memberRepository.countByTournamentIdAndStatus(
                tournament.getId(),
                TournamentMemberStatus.ACTIVE
        );
        long teamCount = teamRepository.countByTournamentId(tournament.getId());
        return tournamentMapper.toResponse(tournament, memberCount, teamCount);
    }

    private TournamentSettings buildSettings(Tournament tournament, TournamentSettingsPayload payload) {
        return TournamentSettings.builder()
                .tournament(tournament)
                .winPoints(payload.winPoints())
                .drawPoints(payload.drawPoints())
                .lossPoints(payload.lossPoints())
                .exactScorePoints(payload.exactScorePoints())
                .winnerPoints(payload.winnerPoints())
                .wrongPoints(payload.wrongPoints())
                // Opcionais no payload: ausentes assumem os defaults (2/1/2).
                .extraTimeExactScorePoints(
                        payload.extraTimeExactScorePoints() != null ? payload.extraTimeExactScorePoints() : 2)
                .extraTimeWinnerPoints(
                        payload.extraTimeWinnerPoints() != null ? payload.extraTimeWinnerPoints() : 1)
                .penaltyWinnerPoints(
                        payload.penaltyWinnerPoints() != null ? payload.penaltyWinnerPoints() : 2)
                .build();
    }

    private void updateSettings(Tournament tournament, TournamentSettingsPayload payload) {
        TournamentSettings settings = tournament.getSettings();
        settings.setWinPoints(payload.winPoints());
        settings.setDrawPoints(payload.drawPoints());
        settings.setLossPoints(payload.lossPoints());
        settings.setExactScorePoints(payload.exactScorePoints());
        settings.setWinnerPoints(payload.winnerPoints());
        settings.setWrongPoints(payload.wrongPoints());
        // Opcionais: ausentes (null) preservam o valor atual, para clientes que não enviam esses campos.
        if (payload.extraTimeExactScorePoints() != null) {
            settings.setExtraTimeExactScorePoints(payload.extraTimeExactScorePoints());
        }
        if (payload.extraTimeWinnerPoints() != null) {
            settings.setExtraTimeWinnerPoints(payload.extraTimeWinnerPoints());
        }
        if (payload.penaltyWinnerPoints() != null) {
            settings.setPenaltyWinnerPoints(payload.penaltyWinnerPoints());
        }
    }

    private void applyTiebreakCriteria(Tournament tournament, List<TiebreakCriteria> criteria) {
        Set<TiebreakCriteria> unique = new HashSet<>(criteria);
        if (unique.size() != criteria.size()) {
            throw new BusinessException(
                    "Tiebreak criteria must not contain duplicates",
                    HttpStatus.BAD_REQUEST
            );
        }
        boolean hadPrevious = !tournament.getTiebreakCriteria().isEmpty();
        tournament.getTiebreakCriteria().clear();
        if (hadPrevious) {
            tournamentRepository.flush();
        }
        for (int i = 0; i < criteria.size(); i++) {
            tournament.getTiebreakCriteria().add(
                    TournamentTiebreakCriterion.builder()
                            .tournament(tournament)
                            .criteria(criteria.get(i))
                            .position(i)
                            .build()
            );
        }
    }

    private void validateCapacity(Tournament tournament, Integer newMaxParticipants, Integer newMaxTeams) {
        if (newMaxParticipants != null) {
            long activeMembers = memberRepository.countByTournamentIdAndStatus(
                    tournament.getId(),
                    TournamentMemberStatus.ACTIVE
            );
            if (newMaxParticipants < activeMembers) {
                throw new TournamentNotEditableException(
                        tournament.getStatus(),
                        "maxParticipants below current active members (" + activeMembers + ")"
                );
            }
        }
        if (newMaxTeams != null) {
            long currentTeams = teamRepository.countByTournamentId(tournament.getId());
            if (newMaxTeams < currentTeams) {
                throw new TournamentNotEditableException(
                        tournament.getStatus(),
                        "maxTeams below current linked teams (" + currentTeams + ")"
                );
            }
        }
    }

    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
