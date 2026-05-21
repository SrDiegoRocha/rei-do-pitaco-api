package com.example.futbet.mapper;

import com.example.futbet.dto.response.TournamentResponse;
import com.example.futbet.dto.response.TournamentSettingsResponse;
import com.example.futbet.entity.Tournament;
import com.example.futbet.entity.TournamentSettings;
import com.example.futbet.entity.TournamentTiebreakCriterion;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class TournamentMapper {

    public TournamentResponse toResponse(Tournament tournament, long memberCount, long teamCount) {
        TournamentSettingsResponse settings = toSettingsResponse(
                tournament.getSettings(),
                tournament.getTiebreakCriteria()
        );
        TournamentResponse.TournamentOwnerSummary owner = new TournamentResponse.TournamentOwnerSummary(
                tournament.getOwner().getPublicId(),
                tournament.getOwner().getName()
        );
        return new TournamentResponse(
                tournament.getPublicId(),
                tournament.getName(),
                tournament.getDescription(),
                tournament.getInviteCode(),
                tournament.getPrivacy(),
                tournament.getType(),
                tournament.getStatus(),
                tournament.getMaxParticipants(),
                tournament.getMaxTeams(),
                owner,
                settings,
                memberCount,
                teamCount,
                tournament.getCreatedAt(),
                tournament.getUpdatedAt()
        );
    }

    private TournamentSettingsResponse toSettingsResponse(
            TournamentSettings settings,
            List<TournamentTiebreakCriterion> criteria
    ) {
        if (settings == null) {
            return null;
        }
        return new TournamentSettingsResponse(
                settings.getWinPoints(),
                settings.getDrawPoints(),
                settings.getLossPoints(),
                settings.getExactScorePoints(),
                settings.getWinnerPoints(),
                settings.getWrongPoints(),
                settings.getGroupsCount(),
                settings.getQualifiersPerGroup(),
                settings.getPlaysInsideGroupOnly(),
                settings.getMatchGenerationMode(),
                settings.getMatchLegMode(),
                criteria.stream()
                        .sorted(Comparator.comparingInt(TournamentTiebreakCriterion::getPosition))
                        .map(TournamentTiebreakCriterion::getCriteria)
                        .toList()
        );
    }
}
