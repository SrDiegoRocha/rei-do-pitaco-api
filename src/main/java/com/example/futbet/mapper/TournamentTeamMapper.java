package com.example.futbet.mapper;

import com.example.futbet.dto.response.TournamentTeamResponse;
import com.example.futbet.entity.Team;
import com.example.futbet.entity.TournamentTeam;
import org.springframework.stereotype.Component;

@Component
public class TournamentTeamMapper {

    public TournamentTeamResponse toResponse(TournamentTeam tournamentTeam) {
        Team team = tournamentTeam.getTeam();
        return new TournamentTeamResponse(
                team.getPublicId(),
                team.getName(),
                team.getShortName(),
                team.getBadgeUrl(),
                team.getPrimaryColor(),
                team.getSecondaryColor(),
                tournamentTeam.getAddedAt()
        );
    }
}
