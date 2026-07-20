package com.example.reidopitaco.mapper;

import com.example.reidopitaco.dto.response.TournamentTeamResponse;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.TournamentTeam;
import com.example.reidopitaco.service.AssetUrlResolver;
import org.springframework.stereotype.Component;

@Component
public class TournamentTeamMapper {

    private final AssetUrlResolver assetUrlResolver;

    public TournamentTeamMapper(AssetUrlResolver assetUrlResolver) {
        this.assetUrlResolver = assetUrlResolver;
    }

    public TournamentTeamResponse toResponse(TournamentTeam tournamentTeam) {
        Team team = tournamentTeam.getTeam();
        return new TournamentTeamResponse(
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
                tournamentTeam.getAddedAt()
        );
    }
}
