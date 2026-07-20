package com.example.reidopitaco.service;

import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.enums.MatchLegMode;
import com.example.reidopitaco.enums.MatchStatus;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.repository.MatchRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Lógica de apoio ao palpite de pênaltis: se um empate neste confronto pode ir aos pênaltis
 * (elegibilidade) e o placar agregado das pernas anteriores, orientado a uma partida específica.
 * Fonte única reutilizada pelo {@code MatchMapper} (expõe no {@code MatchResponse}) e pelo
 * {@code PredictionService} (valida o palpite). Os métodos {@code isEligible}/{@code aggregateBefore}
 * são puros (recebem as pernas prontas); {@link #resolve} carrega as pernas só quando TWO_LEGGED.
 */
@Component
public class MatchPenaltyHelper {

    private final MatchRepository matchRepository;
    private final MatchLegModeResolver legModeResolver;

    public MatchPenaltyHelper(MatchRepository matchRepository, MatchLegModeResolver legModeResolver) {
        this.matchRepository = matchRepository;
        this.legModeResolver = legModeResolver;
    }

    /** Dados de pênalti de uma partida, orientados a ela. */
    public record PenaltyInfo(boolean eligible, int aggregateBeforeHome, int aggregateBeforeAway) {
    }

    /**
     * Resolve os dados para o {@code MatchResponse}. Usa o modo de pernas <b>efetivo</b> do
     * confronto ({@link MatchLegModeResolver} — a rodada final pode ter modo próprio); em jogo
     * único efetivo e fora de mata-mata decide sem carregar as pernas.
     */
    public PenaltyInfo resolve(Match match) {
        var phase = match.getPhase();
        if (phase.getPhaseType() != TournamentPhaseType.KNOCKOUT) {
            return new PenaltyInfo(false, 0, 0);
        }
        if (legModeResolver.effectiveLegMode(match) == MatchLegMode.SINGLE) {
            return new PenaltyInfo(true, 0, 0);
        }
        List<Match> legs = matchRepository.findAllByTieId(match.getTieId());
        int[] agg = aggregateBefore(match, legs);
        return new PenaltyInfo(isEligible(match, legs), agg[0], agg[1]);
    }

    /**
     * Um empate neste confronto pode ir aos pênaltis no palpite: jogo único (efetivo) de KO, ou a
     * perna de volta (maior {@code round}) de um confronto de ida-e-volta. {@code legs} = pernas.
     */
    public boolean isEligible(Match match, List<Match> legs) {
        var phase = match.getPhase();
        if (phase.getPhaseType() != TournamentPhaseType.KNOCKOUT) {
            return false;
        }
        if (legModeResolver.effectiveLegMode(match) == MatchLegMode.SINGLE) {
            return true;
        }
        if (legs.size() < 2) {
            return false;
        }
        int maxRound = legs.stream().mapToInt(Match::getRound).max().orElse(match.getRound());
        return match.getRound() == maxRound;
    }

    /**
     * Gols já marcados nas pernas anteriores (COMPLETED, {@code round} menor) do confronto,
     * orientados ao mandante/visitante DESTA partida. {@code [home, away]}. {@code {0, 0}} em
     * jogo único ou quando a perna anterior ainda não foi concluída.
     */
    public int[] aggregateBefore(Match match, List<Match> legs) {
        Team home = match.getHomeTeam();
        Team away = match.getAwayTeam();
        int homeGoals = 0;
        int awayGoals = 0;
        for (Match leg : legs) {
            if (leg.getId().equals(match.getId())) {
                continue;
            }
            if (leg.getStatus() != MatchStatus.COMPLETED) {
                continue;
            }
            if (leg.getRound() >= match.getRound()) {
                continue; // só pernas anteriores
            }
            homeGoals += goalsOf(leg, home);
            awayGoals += goalsOf(leg, away);
        }
        return new int[]{homeGoals, awayGoals};
    }

    private int goalsOf(Match leg, Team team) {
        if (team.getId().equals(leg.getHomeTeam().getId())) {
            return leg.getHomeScore() == null ? 0 : leg.getHomeScore();
        }
        if (team.getId().equals(leg.getAwayTeam().getId())) {
            return leg.getAwayScore() == null ? 0 : leg.getAwayScore();
        }
        return 0;
    }
}
