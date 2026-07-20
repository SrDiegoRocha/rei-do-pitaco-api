package com.example.reidopitaco.mapper;

import com.example.reidopitaco.dto.response.MatchResponse;
import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.PhaseGroup;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.service.MatchLegModeResolver;
import com.example.reidopitaco.service.MatchPenaltyHelper;
import org.springframework.stereotype.Component;

@Component
public class MatchMapper {

    private final MatchPenaltyHelper penaltyHelper;
    private final MatchLegModeResolver legModeResolver;

    public MatchMapper(MatchPenaltyHelper penaltyHelper, MatchLegModeResolver legModeResolver) {
        this.penaltyHelper = penaltyHelper;
        this.legModeResolver = legModeResolver;
    }

    public MatchResponse toResponse(Match match) {
        PhaseGroup group = match.getGroup();
        MatchPenaltyHelper.PenaltyInfo penalty = penaltyHelper.resolve(match);
        return new MatchResponse(
                match.getPublicId(),
                match.getPhase().getPublicId(),
                group != null ? group.getPublicId() : null,
                group != null ? group.getName() : null,
                match.getRound(),
                match.getTieId(),
                match.getMatchType(),
                legModeResolver.effectiveLegMode(match),
                toTeamRef(match.getHomeTeam()),
                toTeamRef(match.getAwayTeam()),
                match.getScheduledAt(),
                match.getHomeScore(),
                match.getAwayScore(),
                match.getHomeExtraTimeScore(),
                match.getAwayExtraTimeScore(),
                match.getHomePenalties(),
                match.getAwayPenalties(),
                penalty.eligible(),
                penalty.aggregateBeforeHome(),
                penalty.aggregateBeforeAway(),
                match.getStatus(),
                match.getCreatedAt(),
                match.getUpdatedAt()
        );
    }

    public MatchResponse.TeamRef toTeamRef(Team team) {
        return new MatchResponse.TeamRef(
                team.getPublicId(),
                team.getName(),
                team.getShortName(),
                team.getBadgeUrl(),
                team.getPrimaryColor(),
                team.getSecondaryColor(),
                team.getTeamType(),
                team.getCountryCode()
        );
    }
}
