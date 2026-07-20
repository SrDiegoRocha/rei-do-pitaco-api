package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.request.CreateTeamRequest;
import com.example.reidopitaco.dto.request.UpdateTeamRequest;
import com.example.reidopitaco.dto.response.TeamResponse;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.User;
import com.example.reidopitaco.enums.TeamScope;
import com.example.reidopitaco.enums.TeamType;
import com.example.reidopitaco.exception.SystemTeamReadOnlyException;
import com.example.reidopitaco.exception.TeamNameAlreadyInUseException;
import com.example.reidopitaco.exception.TeamNotFoundException;
import com.example.reidopitaco.mapper.TeamMapper;
import com.example.reidopitaco.repository.TeamRepository;
import com.example.reidopitaco.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final TeamMapper teamMapper;

    public TeamService(
            TeamRepository teamRepository,
            UserRepository userRepository,
            TeamMapper teamMapper
    ) {
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.teamMapper = teamMapper;
    }

    @Transactional
    public TeamResponse create(UUID ownerPublicId, CreateTeamRequest request) {
        User owner = userRepository.findByPublicId(ownerPublicId)
                .orElseThrow(TeamNotFoundException::new);

        String name = request.name().trim();
        if (teamRepository.existsByOwnerPublicIdAndNameIgnoreCaseAndActiveTrue(ownerPublicId, name)) {
            throw new TeamNameAlreadyInUseException();
        }

        Team team = Team.builder()
                .owner(owner)
                .name(name)
                .shortName(normalize(request.shortName()))
                .badgeUrl(request.badgeUrl())
                .primaryColor(request.primaryColor())
                .secondaryColor(request.secondaryColor())
                .build();

        Team saved = teamRepository.save(team);
        return teamMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<TeamResponse> list(UUID ownerPublicId, TeamScope scope, TeamType type, String league, Pageable pageable) {
        TeamScope effectiveScope = scope != null ? scope : TeamScope.MINE;
        boolean includeMine = effectiveScope == TeamScope.MINE || effectiveScope == TeamScope.ALL;
        boolean includeSystem = effectiveScope == TeamScope.SYSTEM || effectiveScope == TeamScope.ALL;
        String leagueSlug = normalize(league);
        return teamRepository.search(includeMine, includeSystem, ownerPublicId, type, leagueSlug, pageable)
                .map(teamMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public TeamResponse getById(UUID ownerPublicId, UUID teamPublicId) {
        Team team = teamRepository.findByPublicIdAndActiveTrue(teamPublicId)
                .orElseThrow(TeamNotFoundException::new);
        // Times do sistema são visíveis a todos; os de usuário, só pro dono.
        if (!team.isSystem() && !isOwnedBy(team, ownerPublicId)) {
            throw new TeamNotFoundException();
        }
        return teamMapper.toResponse(team);
    }

    @Transactional
    public TeamResponse update(UUID ownerPublicId, UUID teamPublicId, UpdateTeamRequest request) {
        Team team = teamRepository.findByPublicIdAndActiveTrue(teamPublicId)
                .orElseThrow(TeamNotFoundException::new);
        if (team.isSystem()) {
            throw new SystemTeamReadOnlyException();
        }
        if (!isOwnedBy(team, ownerPublicId)) {
            throw new TeamNotFoundException();
        }

        String newName = request.name().trim();
        boolean nameChanged = !team.getName().equalsIgnoreCase(newName);
        if (nameChanged && teamRepository.existsByOwnerPublicIdAndNameIgnoreCaseAndActiveTrue(ownerPublicId, newName)) {
            throw new TeamNameAlreadyInUseException();
        }

        team.setName(newName);
        team.setShortName(normalize(request.shortName()));
        team.setBadgeUrl(request.badgeUrl());
        team.setPrimaryColor(request.primaryColor());
        team.setSecondaryColor(request.secondaryColor());

        Team saved = teamRepository.saveAndFlush(team);
        return teamMapper.toResponse(saved);
    }

    @Transactional
    public void delete(UUID ownerPublicId, UUID teamPublicId) {
        Team team = teamRepository.findByPublicIdAndActiveTrue(teamPublicId)
                .orElseThrow(TeamNotFoundException::new);
        if (team.isSystem()) {
            throw new SystemTeamReadOnlyException();
        }
        if (!isOwnedBy(team, ownerPublicId)) {
            throw new TeamNotFoundException();
        }
        team.setActive(false);
    }

    private boolean isOwnedBy(Team team, UUID ownerPublicId) {
        return team.getOwner() != null && team.getOwner().getPublicId().equals(ownerPublicId);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
