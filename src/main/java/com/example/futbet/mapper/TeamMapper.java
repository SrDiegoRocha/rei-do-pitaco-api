package com.example.futbet.mapper;

import com.example.futbet.dto.response.TeamResponse;
import com.example.futbet.entity.Team;
import org.springframework.stereotype.Component;

@Component
public class TeamMapper {

    public TeamResponse toResponse(Team team) {
        return new TeamResponse(
                team.getPublicId(),
                team.getName(),
                team.getShortName(),
                team.getBadgeUrl(),
                team.getPrimaryColor(),
                team.getSecondaryColor(),
                team.getCreatedAt(),
                team.getUpdatedAt()
        );
    }
}
