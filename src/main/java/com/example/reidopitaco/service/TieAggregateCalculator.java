package com.example.reidopitaco.service;

import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.enums.MatchStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Calcula o agregado de um confronto de mata-mata (uma ou duas pernas que compartilham o mesmo
 * {@code tieId}) e determina o vencedor. Fonte única de verdade reutilizada tanto pela geração
 * automática de rodadas ({@link MatchGenerationService}) quanto pela leitura do chaveamento
 * ({@link BracketService}).
 */
@Component
public class TieAggregateCalculator {

    /**
     * Soma os placares das pernas COMPLETED orientando tudo para o mandante/visitante da primeira
     * perna. Pernas não concluídas (SCHEDULED/CANCELLED) não somam. Empate no agregado é decidido
     * pelos pênaltis da perna decisiva (se houver); persistindo o empate, {@code winner == null}.
     */
    public TieAggregate compute(List<Match> legs) {
        if (legs == null || legs.isEmpty()) {
            return new TieAggregate(null, null, 0, 0, null, null, null);
        }
        Team home = legs.get(0).getHomeTeam();
        Team away = legs.get(0).getAwayTeam();
        int homeAgg = 0;
        int awayAgg = 0;
        Integer homePens = null;
        Integer awayPens = null;
        int penDeciderRound = Integer.MIN_VALUE;
        for (Match m : legs) {
            if (m.getStatus() != MatchStatus.COMPLETED) {
                continue;
            }
            boolean orientedHome = m.getHomeTeam().getId().equals(home.getId());
            // Em jogo único de mata-mata, a prorrogação é o placar decisivo (cumulativo) — usa-a
            // quando presente. Em ida-e-volta não há prorrogação, então cai sempre no tempo normal.
            int hs = effectiveHome(m);
            int as = effectiveAway(m);
            if (orientedHome) {
                homeAgg += hs;
                awayAgg += as;
            } else {
                homeAgg += as;
                awayAgg += hs;
            }
            // Pênaltis: usa a perna concluída de maior round (a decisiva) que os tiver.
            if (m.getHomePenalties() != null && m.getAwayPenalties() != null
                    && m.getRound() >= penDeciderRound) {
                penDeciderRound = m.getRound();
                homePens = orientedHome ? m.getHomePenalties() : m.getAwayPenalties();
                awayPens = orientedHome ? m.getAwayPenalties() : m.getHomePenalties();
            }
        }
        Team winner = null;
        if (homeAgg > awayAgg) {
            winner = home;
        } else if (awayAgg > homeAgg) {
            winner = away;
        } else if (homePens != null && awayPens != null) {
            if (homePens > awayPens) {
                winner = home;
            } else if (awayPens > homePens) {
                winner = away;
            }
        }
        return new TieAggregate(home, away, homeAgg, awayAgg, homePens, awayPens, winner);
    }

    /** Placar decisivo do mandante da perna: prorrogação se lançada, senão tempo normal. */
    private int effectiveHome(Match m) {
        if (m.getHomeExtraTimeScore() != null) {
            return m.getHomeExtraTimeScore();
        }
        return m.getHomeScore() == null ? 0 : m.getHomeScore();
    }

    /** Placar decisivo do visitante da perna: prorrogação se lançada, senão tempo normal. */
    private int effectiveAway(Match m) {
        if (m.getAwayExtraTimeScore() != null) {
            return m.getAwayExtraTimeScore();
        }
        return m.getAwayScore() == null ? 0 : m.getAwayScore();
    }

    /** Vencedor do confronto, ou {@code null} se empate no agregado. */
    public Team loserOf(TieAggregate aggregate) {
        if (aggregate.winner() == null) {
            return null;
        }
        return aggregate.winner().getId().equals(aggregate.homeTeam().getId())
                ? aggregate.awayTeam()
                : aggregate.homeTeam();
    }

    public record TieAggregate(
            Team homeTeam,
            Team awayTeam,
            int homeAggregate,
            int awayAggregate,
            Integer homePenalties,
            Integer awayPenalties,
            Team winner
    ) {
    }
}
