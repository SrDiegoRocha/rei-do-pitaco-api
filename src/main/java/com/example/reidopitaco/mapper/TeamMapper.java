package com.example.reidopitaco.mapper;

import com.example.reidopitaco.dto.response.TeamResponse;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.service.AssetUrlResolver;
import org.springframework.stereotype.Component;

@Component
public class TeamMapper {

    private final AssetUrlResolver assetUrlResolver;

    public TeamMapper(AssetUrlResolver assetUrlResolver) {
        this.assetUrlResolver = assetUrlResolver;
    }

    public TeamResponse toResponse(Team team) {
        return new TeamResponse(
                team.getPublicId(),
                team.getName(),
                team.getShortName(),
                assetUrlResolver.resolve(team.getBadgeUrl()),
                team.getPrimaryColor(),
                team.getSecondaryColor(),
                team.isSystem(),
                team.getTeamType(),
                team.getCountryCode(),
                team.getLeagueSlug(),
                team.getLeagueName(),
                team.getCreatedAt(),
                team.getUpdatedAt()
        );
    }
}
