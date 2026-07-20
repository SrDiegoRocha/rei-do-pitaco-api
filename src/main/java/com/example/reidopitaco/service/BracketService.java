package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.response.BracketResponse;
import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.enums.MatchStatus;
import com.example.reidopitaco.enums.MatchType;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.exception.InvalidPhaseTypeException;
import com.example.reidopitaco.exception.PhaseNotFoundException;
import com.example.reidopitaco.mapper.MatchMapper;
import com.example.reidopitaco.repository.MatchRepository;
import com.example.reidopitaco.repository.TournamentPhaseRepository;
import com.example.reidopitaco.service.TieAggregateCalculator.TieAggregate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Monta o chaveamento de uma fase KNOCKOUT. Diferente de {@link StandingsService} (tabela de
 * liga, válida para ROUND_ROBIN/GROUPS), aqui o que importa é a árvore de confrontos: pernas
 * agrupadas por {@code tieId}, placar agregado e vencedor.
 */
@Service
public class BracketService {

    private final TournamentPhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final MatchMapper matchMapper;
    private final TieAggregateCalculator tieCalculator;
    private final TournamentAccessGuard accessGuard;

    public BracketService(
            TournamentPhaseRepository phaseRepository,
            MatchRepository matchRepository,
            MatchMapper matchMapper,
            TieAggregateCalculator tieCalculator,
            TournamentAccessGuard accessGuard
    ) {
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.matchMapper = matchMapper;
        this.tieCalculator = tieCalculator;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public BracketResponse compute(UUID requesterPublicId, UUID tournamentPublicId, UUID phasePublicId) {
        accessGuard.requireViewable(requesterPublicId, tournamentPublicId);
        TournamentPhase phase = phaseRepository
                .findByPublicIdAndTournamentPublicId(phasePublicId, tournamentPublicId)
                .orElseThrow(PhaseNotFoundException::new);

        if (phase.getPhaseType() != TournamentPhaseType.KNOCKOUT) {
            throw new InvalidPhaseTypeException(
                    "Bracket is only available for KNOCKOUT phases; use /standings"
            );
        }

        List<Match> matches = matchRepository.findAllByPhasePublicId(phasePublicId);
        if (matches.isEmpty()) {
            return new BracketResponse(phasePublicId, phase.getName(), List.of());
        }

        // Agrupa as pernas por confronto (tieId).
        Map<UUID, List<Match>> byTie = new LinkedHashMap<>();
        for (Match m : matches) {
            byTie.computeIfAbsent(m.getTieId(), k -> new ArrayList<>()).add(m);
        }

        List<TieData> ties = new ArrayList<>();
        for (List<Match> legs : byTie.values()) {
            legs.sort(Comparator.comparingInt(Match::getRound)
                    .thenComparing(Match::getCreatedAt));
            TieData tie = new TieData();
            tie.tieId = legs.get(0).getTieId();
            tie.legs = legs;
            tie.aggregate = tieCalculator.compute(legs);
            tie.minRound = legs.get(0).getRound();
            tie.firstCreatedAt = legs.get(0).getCreatedAt();
            tie.complete = legs.stream().noneMatch(m -> m.getStatus() == MatchStatus.SCHEDULED);
            tie.thirdPlace = legs.get(0).getMatchType() == MatchType.THIRD_PLACE;
            ties.add(tie);
        }

        // Agrupa confrontos por rodada (menor round de cada confronto cobre ida e volta).
        TreeMap<Integer, List<TieData>> byRound = new TreeMap<>();
        for (TieData tie : ties) {
            byRound.computeIfAbsent(tie.minRound, k -> new ArrayList<>()).add(tie);
        }
        List<List<TieData>> orderedRounds = new ArrayList<>(byRound.values());
        for (List<TieData> round : orderedRounds) {
            // 3º lugar por último dentro da rodada (a final aparece primeiro).
            round.sort(Comparator.comparing((TieData t) -> t.thirdPlace)
                    .thenComparing(t -> t.firstCreatedAt));
        }

        int roundOneTieCount = orderedRounds.get(0).size();
        List<BracketResponse.BracketRound> rounds = new ArrayList<>(orderedRounds.size());
        for (int i = 0; i < orderedRounds.size(); i++) {
            int expectedTies = roundOneTieCount >> i;
            List<BracketResponse.BracketTie> tieDtos = orderedRounds.get(i).stream()
                    .map(this::toTieDto)
                    .toList();
            rounds.add(new BracketResponse.BracketRound(i + 1, labelForRound(expectedTies, i + 1), tieDtos));
        }

        return new BracketResponse(phasePublicId, phase.getName(), rounds);
    }

    private BracketResponse.BracketTie toTieDto(TieData tie) {
        TieAggregate agg = tie.aggregate;
        return new BracketResponse.BracketTie(
                tie.tieId,
                agg.homeTeam() != null ? matchMapper.toTeamRef(agg.homeTeam()) : null,
                agg.awayTeam() != null ? matchMapper.toTeamRef(agg.awayTeam()) : null,
                agg.homeAggregate(),
                agg.awayAggregate(),
                agg.homePenalties(),
                agg.awayPenalties(),
                agg.winner() != null ? matchMapper.toTeamRef(agg.winner()) : null,
                tie.complete,
                tie.thirdPlace,
                tie.legs.stream().map(matchMapper::toResponse).toList()
        );
    }

    /** Rótulo da rodada pelo nº esperado de confrontos. Reusado pelo template do Pick'em. */
    static String labelForRound(int expectedTies, int ordinal) {
        return switch (expectedTies) {
            case 1 -> "Final";
            case 2 -> "Semifinals";
            case 4 -> "Quarterfinals";
            case 8 -> "Round of 16";
            case 16 -> "Round of 32";
            case 32 -> "Round of 64";
            default -> "Round " + ordinal;
        };
    }

    private static class TieData {
        UUID tieId;
        List<Match> legs;
        TieAggregate aggregate;
        int minRound;
        Instant firstCreatedAt;
        boolean complete;
        boolean thirdPlace;
    }
}
