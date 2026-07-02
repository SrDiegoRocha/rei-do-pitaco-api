package com.example.reidopitaco.mapper;

import com.example.reidopitaco.dto.response.UserMatchResponse;
import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.PhaseGroup;
import com.example.reidopitaco.entity.Prediction;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.entity.TournamentSettings;
import org.springframework.stereotype.Component;

@Component
public class UserMatchMapper {

    private final MatchMapper matchMapper;

    public UserMatchMapper(MatchMapper matchMapper) {
        this.matchMapper = matchMapper;
    }

    /**
     * Monta um item do feed pessoal. {@code myPrediction} pode ser {@code null}
     * (usuário ainda não palpitou).
     */
    public UserMatchResponse toResponse(Match match, Prediction myPrediction) {
        TournamentPhase phase = match.getPhase();
        Tournament tournament = phase.getTournament();
        PhaseGroup group = match.getGroup();

        UserMatchResponse.GroupRef groupRef = group == null ? null : new UserMatchResponse.GroupRef(
                group.getPublicId(),
                group.getName(),
                group.getPosition()
        );

        UserMatchResponse.MyPrediction predictionRef = myPrediction == null ? null : new UserMatchResponse.MyPrediction(
                myPrediction.getPublicId(),
                myPrediction.getHomeScore(),
                myPrediction.getAwayScore(),
                myPrediction.getHomeExtraTimeScore(),
                myPrediction.getAwayExtraTimeScore(),
                myPrediction.getPenaltyWinner(),
                myPrediction.getPoints()
        );

        TournamentSettings settings = tournament.getSettings();
        UserMatchResponse.ScoringRef scoringRef = settings == null ? null : new UserMatchResponse.ScoringRef(
                settings.getExactScorePoints(),
                settings.getWinnerPoints(),
                settings.getWrongPoints(),
                settings.getExtraTimeExactScorePoints(),
                settings.getExtraTimeWinnerPoints(),
                settings.getPenaltyWinnerPoints()
        );

        return new UserMatchResponse(
                matchMapper.toResponse(match),
                new UserMatchResponse.TournamentRef(
                        tournament.getPublicId(),
                        tournament.getName(),
                        tournament.getPrivacy(),
                        tournament.getStatus(),
                        scoringRef
                ),
                new UserMatchResponse.PhaseRef(
                        phase.getPublicId(),
                        phase.getName(),
                        phase.getPosition(),
                        phase.getPhaseType(),
                        phase.getMatchLegMode()
                ),
                groupRef,
                predictionRef
        );
    }
}
