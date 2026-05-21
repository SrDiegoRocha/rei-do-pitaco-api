package com.example.futbet.service;

import com.example.futbet.dto.request.CreateTeamRequest;
import com.example.futbet.dto.request.UpdateTeamRequest;
import com.example.futbet.dto.response.TeamResponse;
import com.example.futbet.entity.Team;
import com.example.futbet.entity.User;
import com.example.futbet.exception.TeamNameAlreadyInUseException;
import com.example.futbet.exception.TeamNotFoundException;
import com.example.futbet.mapper.TeamMapper;
import com.example.futbet.repository.TeamRepository;
import com.example.futbet.repository.UserRepository;
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
    public Page<TeamResponse> list(UUID ownerPublicId, Pageable pageable) {
        return teamRepository.findAllByOwnerPublicIdAndActiveTrue(ownerPublicId, pageable)
                .map(teamMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public TeamResponse getById(UUID ownerPublicId, UUID teamPublicId) {
        Team team = teamRepository.findByPublicIdAndOwnerPublicIdAndActiveTrue(teamPublicId, ownerPublicId)
                .orElseThrow(TeamNotFoundException::new);
        return teamMapper.toResponse(team);
    }

    @Transactional
    public TeamResponse update(UUID ownerPublicId, UUID teamPublicId, UpdateTeamRequest request) {
        Team team = teamRepository.findByPublicIdAndOwnerPublicIdAndActiveTrue(teamPublicId, ownerPublicId)
                .orElseThrow(TeamNotFoundException::new);

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
        Team team = teamRepository.findByPublicIdAndOwnerPublicIdAndActiveTrue(teamPublicId, ownerPublicId)
                .orElseThrow(TeamNotFoundException::new);
        team.setActive(false);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
