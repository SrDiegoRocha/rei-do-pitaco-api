package com.example.reidopitaco.mapper;

import com.example.reidopitaco.dto.response.TournamentResponse;
import com.example.reidopitaco.dto.response.TournamentSettingsResponse;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentSettings;
import com.example.reidopitaco.entity.TournamentTiebreakCriterion;
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
                settings.getExtraTimeExactScorePoints(),
                settings.getExtraTimeWinnerPoints(),
                settings.getPenaltyWinnerPoints(),
                criteria.stream()
                        .sorted(Comparator.comparingInt(TournamentTiebreakCriterion::getPosition))
                        .map(TournamentTiebreakCriterion::getCriteria)
                        .toList()
        );
    }
}
