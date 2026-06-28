package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.response.StandingsResponse;
import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.entity.PhaseGroup;
import com.example.reidopitaco.entity.PhaseTeam;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.entity.TournamentSettings;
import com.example.reidopitaco.entity.TournamentZone;
import com.example.reidopitaco.enums.MatchStatus;
import com.example.reidopitaco.enums.TiebreakCriteria;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.enums.ZoneSelectionMode;
import com.example.reidopitaco.exception.InvalidPhaseTypeException;
import com.example.reidopitaco.exception.PhaseNotFoundException;
import com.example.reidopitaco.repository.MatchRepository;
import com.example.reidopitaco.repository.PhaseGroupRepository;
import com.example.reidopitaco.repository.PhaseTeamRepository;
import com.example.reidopitaco.repository.TournamentPhaseRepository;
import com.example.reidopitaco.repository.TournamentZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

@Service
public class StandingsService {

    private final TournamentPhaseRepository phaseRepository;
    private final PhaseGroupRepository groupRepository;
    private final PhaseTeamRepository phaseTeamRepository;
    private final MatchRepository matchRepository;
    private final TournamentZoneRepository zoneRepository;
    private final TournamentAccessGuard accessGuard;

    public StandingsService(
            TournamentPhaseRepository phaseRepository,
            PhaseGroupRepository groupRepository,
            PhaseTeamRepository phaseTeamRepository,
            MatchRepository matchRepository,
            TournamentZoneRepository zoneRepository,
            TournamentAccessGuard accessGuard
    ) {
        this.phaseRepository = phaseRepository;
        this.groupRepository = groupRepository;
        this.phaseTeamRepository = phaseTeamRepository;
        this.matchRepository = matchRepository;
        this.zoneRepository = zoneRepository;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public StandingsResponse compute(UUID requesterPublicId, UUID tournamentPublicId, UUID phasePublicId) {
        Tournament tournament = accessGuard.requireViewable(requesterPublicId, tournamentPublicId);
        TournamentPhase phase = phaseRepository
                .findByPublicIdAndTournamentPublicId(phasePublicId, tournamentPublicId)
                .orElseThrow(PhaseNotFoundException::new);
        if (phase.getPhaseType() == TournamentPhaseType.KNOCKOUT) {
            throw new InvalidPhaseTypeException(
                    "Standings are not available for KNOCKOUT phases; use /bracket"
            );
        }
        return computeFor(tournament, phasePublicId);
    }

    /**
     * Cálculo da classificação sem checagem de visibilidade nem de tipo de fase. Para uso interno
     * por quem já validou o acesso (ex.: {@link PhaseFinalizeService}, que é owner-only). O endpoint
     * público passa por {@link #compute}, que aplica o {@link TournamentAccessGuard} e bloqueia
     * fases KNOCKOUT.
     */
    StandingsResponse computeFor(Tournament tournament, UUID phasePublicId) {
        TournamentPhase phase = phaseRepository
                .findByPublicIdAndTournamentPublicId(phasePublicId, tournament.getPublicId())
                .orElseThrow(PhaseNotFoundException::new);

        // Times e partidas da fase: buscados UMA vez (com fetch join) e reutilizados por grupo.
        // Buscar dentro do loop de grupos multiplicava as idas ao banco — caro com banco remoto.
        List<PhaseTeam> phaseTeams = phaseTeamRepository.findAllByPhasePublicId(phasePublicId);
        List<Match> allMatches = matchRepository.findAllByPhasePublicId(phasePublicId);

        // 1. Calcula a tabela ordenada de cada grupo (ou bloco único em ROUND_ROBIN).
        List<GroupTable> tables = new ArrayList<>();
        if (phase.getPhaseType() == TournamentPhaseType.GROUPS) {
            List<PhaseGroup> phaseGroups = groupRepository
                    .findAllByPhasePublicIdOrderByPositionAsc(phasePublicId);
            for (PhaseGroup group : phaseGroups) {
                tables.add(new GroupTable(group, orderedTable(tournament, group, phaseTeams, allMatches)));
            }
        } else {
            tables.add(new GroupTable(null, orderedTable(tournament, null, phaseTeams, allMatches)));
        }

        // 2. Resolve as zonas: por posição (ALL) e os classificados de cada zona BEST_RANKED.
        List<TournamentZone> zones = zoneRepository.findAllByPhaseIdOrderByPositionAsc(phase.getId());
        Set<Long> bestRankedQualifiers =
                bestRankedQualifiers(tables, zones, orderedTiebreaks(tournament), allMatches);

        // 3. Monta as linhas já com o desfecho de zona.
        List<StandingsResponse.GroupStandings> groups = new ArrayList<>(tables.size());
        for (GroupTable t : tables) {
            List<StandingsResponse.StandingRow> rows = new ArrayList<>(t.accumulators().size());
            for (int i = 0; i < t.accumulators().size(); i++) {
                StandingAccumulator a = t.accumulators().get(i);
                int position = i + 1;
                rows.add(buildRow(a, position, zoneForPosition(zones, position), bestRankedQualifiers));
            }
            groups.add(new StandingsResponse.GroupStandings(
                    t.group() != null ? t.group().getPublicId() : null,
                    t.group() != null ? t.group().getName() : null,
                    rows
            ));
        }

        return new StandingsResponse(phasePublicId, groups);
    }

    private StandingsResponse.StandingRow buildRow(
            StandingAccumulator a,
            int position,
            TournamentZone zone,
            Set<Long> bestRankedQualifiers
    ) {
        UUID zoneId = null;
        String zoneName = null;
        UUID nextPhaseId = null;
        String nextPhaseName = null;
        boolean qualifies = false;

        if (zone != null) {
            zoneId = zone.getPublicId();
            zoneName = zone.getName();
            if (zone.getNextPhase() != null) {
                nextPhaseId = zone.getNextPhase().getPublicId();
                nextPhaseName = zone.getNextPhase().getName();
                qualifies = zone.getSelectionMode() == ZoneSelectionMode.BEST_RANKED
                        ? bestRankedQualifiers.contains(a.team.getId())
                        : true;
            }
        }

        return new StandingsResponse.StandingRow(
                position,
                a.team.getPublicId(),
                a.team.getName(),
                a.team.getShortName(),
                a.team.getBadgeUrl(),
                a.team.getTeamType(),
                a.team.getCountryCode(),
                a.played, a.wins, a.draws, a.losses,
                a.goalsFor, a.goalsAgainst,
                a.goalsFor - a.goalsAgainst,
                a.points,
                zoneId, zoneName, nextPhaseId, nextPhaseName, qualifies
        );
    }

    /** Zona cuja faixa de posições cobre {@code position}. Zonas não se sobrepõem (validado). */
    private TournamentZone zoneForPosition(List<TournamentZone> zones, int position) {
        for (TournamentZone z : zones) {
            if (position >= z.getFromPosition() && position <= z.getToPosition()) {
                return z;
            }
        }
        return null;
    }

    /**
     * IDs (internos) dos times que se classificam pelas zonas BEST_RANKED. Para cada zona desse tipo,
     * junta os times da posição-alvo de todos os grupos, ranqueia entre si com os critérios de
     * desempate configurados no torneio (mesma fonte de verdade do {@code finalize}) e pega os
     * {@code bestRankedCount} melhores. Provisório enquanto a fase não terminou.
     */
    private Set<Long> bestRankedQualifiers(
            List<GroupTable> tables,
            List<TournamentZone> zones,
            List<TiebreakCriteria> tiebreaks,
            List<Match> allMatches
    ) {
        Comparator<StandingAccumulator> comparator = bestRankedComparator(
                tiebreaks,
                s -> s.points,
                s -> s.wins,
                s -> s.goalsFor - s.goalsAgainst,
                s -> s.goalsFor,
                s -> s.losses,
                s -> s.team.getName()
        );
        Set<Long> qualifiers = new HashSet<>();
        for (TournamentZone zone : zones) {
            if (zone.getSelectionMode() != ZoneSelectionMode.BEST_RANKED
                    || zone.getNextPhase() == null
                    || zone.getBestRankedCount() == null) {
                continue;
            }
            int index = zone.getFromPosition() - 1; // from == to em BEST_RANKED
            List<StandingAccumulator> candidates = new ArrayList<>();
            for (GroupTable t : tables) {
                if (index >= 0 && index < t.accumulators().size()) {
                    candidates.add(t.accumulators().get(index));
                }
            }
            candidates.sort(comparator);
            candidates.stream()
                    .limit(zone.getBestRankedCount())
                    .forEach(c -> qualifiers.add(c.team.getId()));
        }
        return qualifiers;
    }

    /** Critérios de desempate do torneio, ordenados por {@code position}. */
    private List<TiebreakCriteria> orderedTiebreaks(Tournament tournament) {
        return tournament.getTiebreakCriteria().stream()
                .sorted(Comparator.comparingInt(c -> c.getPosition()))
                .map(c -> c.getCriteria())
                .toList();
    }

    /**
     * Comparador para o ranqueamento cross-grupo dos "melhores N", derivado dos critérios de
     * desempate configurados. {@code HEAD_TO_HEAD} não se aplica aqui — os candidatos vêm de grupos
     * diferentes e nunca se enfrentaram — então vira no-op; os demais critérios ordenam. Genérico
     * para ser reusado tanto sobre {@link StandingAccumulator} (projeção) quanto sobre
     * {@code StandingRow} (finalize), garantindo uma única fonte de verdade.
     */
    static <T> Comparator<T> bestRankedComparator(
            List<TiebreakCriteria> tiebreaks,
            ToIntFunction<T> points,
            ToIntFunction<T> wins,
            ToIntFunction<T> goalDifference,
            ToIntFunction<T> goalsFor,
            ToIntFunction<T> losses,
            Function<T, String> name
    ) {
        Comparator<T> chain = (a, b) -> 0;
        for (TiebreakCriteria criterion : tiebreaks) {
            Comparator<T> step = switch (criterion) {
                case POINTS -> Comparator.comparingInt(points).reversed();
                case WINS -> Comparator.comparingInt(wins).reversed();
                case GOAL_DIFFERENCE -> Comparator.comparingInt(goalDifference).reversed();
                case GOALS_FOR -> Comparator.comparingInt(goalsFor).reversed();
                case FEWEST_LOSSES -> Comparator.comparingInt(losses);
                case HEAD_TO_HEAD -> (a, b) -> 0;
            };
            chain = chain.thenComparing(step);
        }
        return chain.thenComparing(Comparator.comparing(name, String.CASE_INSENSITIVE_ORDER));
    }

    private List<StandingAccumulator> orderedTable(
            Tournament tournament,
            PhaseGroup group,
            List<PhaseTeam> phaseTeams,
            List<Match> allMatches
    ) {
        List<PhaseTeam> teams = phaseTeams.stream()
                .filter(pt -> {
                    if (group == null) return true;
                    return pt.getGroup() != null && pt.getGroup().getId().equals(group.getId());
                })
                .toList();

        Map<Long, StandingAccumulator> table = new HashMap<>();
        for (PhaseTeam pt : teams) {
            table.put(pt.getTeam().getId(), new StandingAccumulator(pt.getTeam()));
        }

        for (Match match : allMatches) {
            if (match.getStatus() != MatchStatus.COMPLETED) {
                continue;
            }
            if (group != null) {
                if (match.getGroup() == null || !match.getGroup().getId().equals(group.getId())) {
                    continue;
                }
            }
            StandingAccumulator home = table.get(match.getHomeTeam().getId());
            StandingAccumulator away = table.get(match.getAwayTeam().getId());
            if (home == null || away == null) {
                continue;
            }
            int homeScore = match.getHomeScore() == null ? 0 : match.getHomeScore();
            int awayScore = match.getAwayScore() == null ? 0 : match.getAwayScore();
            home.played++;
            away.played++;
            home.goalsFor += homeScore;
            home.goalsAgainst += awayScore;
            away.goalsFor += awayScore;
            away.goalsAgainst += homeScore;
            if (homeScore > awayScore) {
                home.wins++;
                away.losses++;
            } else if (homeScore < awayScore) {
                away.wins++;
                home.losses++;
            } else {
                home.draws++;
                away.draws++;
            }
        }

        TournamentSettings settings = tournament.getSettings();
        for (StandingAccumulator acc : table.values()) {
            acc.points = acc.wins * settings.getWinPoints()
                    + acc.draws * settings.getDrawPoints()
                    + acc.losses * settings.getLossPoints();
        }

        List<TiebreakCriteria> tiebreaks = orderedTiebreaks(tournament);

        List<StandingAccumulator> ordered = new ArrayList<>(table.values());
        ordered.sort(buildComparator(tiebreaks, allMatches, group));
        return ordered;
    }

    Comparator<StandingAccumulator> buildComparator(
            List<TiebreakCriteria> tiebreaks,
            List<Match> matches,
            PhaseGroup groupContext
    ) {
        Comparator<StandingAccumulator> chain = (a, b) -> 0;
        for (TiebreakCriteria criterion : tiebreaks) {
            Comparator<StandingAccumulator> step = switch (criterion) {
                case POINTS -> Comparator.comparingInt((StandingAccumulator s) -> s.points).reversed();
                case WINS -> Comparator.comparingInt((StandingAccumulator s) -> s.wins).reversed();
                case GOAL_DIFFERENCE -> Comparator.comparingInt(
                        (StandingAccumulator s) -> s.goalsFor - s.goalsAgainst
                ).reversed();
                case GOALS_FOR -> Comparator.comparingInt((StandingAccumulator s) -> s.goalsFor).reversed();
                case FEWEST_LOSSES -> Comparator.comparingInt((StandingAccumulator s) -> s.losses);
                case HEAD_TO_HEAD -> headToHead(matches, groupContext);
            };
            chain = chain.thenComparing(step);
        }
        chain = chain.thenComparing((a, b) -> a.team.getName().compareToIgnoreCase(b.team.getName()));
        return chain;
    }

    private Comparator<StandingAccumulator> headToHead(List<Match> matches, PhaseGroup groupContext) {
        return (a, b) -> {
            int pointsA = 0;
            int pointsB = 0;
            for (Match m : matches) {
                if (m.getStatus() != MatchStatus.COMPLETED) continue;
                if (groupContext != null) {
                    if (m.getGroup() == null || !m.getGroup().getId().equals(groupContext.getId())) continue;
                }
                Long homeId = m.getHomeTeam().getId();
                Long awayId = m.getAwayTeam().getId();
                Long ida = a.team.getId();
                Long idb = b.team.getId();
                if (!((homeId.equals(ida) && awayId.equals(idb)) || (homeId.equals(idb) && awayId.equals(ida)))) {
                    continue;
                }
                int homeScore = m.getHomeScore() == null ? 0 : m.getHomeScore();
                int awayScore = m.getAwayScore() == null ? 0 : m.getAwayScore();
                if (homeScore > awayScore) {
                    if (homeId.equals(ida)) pointsA += 3;
                    else pointsB += 3;
                } else if (awayScore > homeScore) {
                    if (awayId.equals(ida)) pointsA += 3;
                    else pointsB += 3;
                } else {
                    pointsA += 1;
                    pointsB += 1;
                }
            }
            return Integer.compare(pointsB, pointsA);
        };
    }

    static class StandingAccumulator {
        final Team team;
        int played, wins, draws, losses;
        int goalsFor, goalsAgainst;
        int points;

        StandingAccumulator(Team team) {
            this.team = team;
        }
    }

    /** Tabela ordenada de um grupo (ou bloco único em ROUND_ROBIN, com {@code group == null}). */
    private record GroupTable(PhaseGroup group, List<StandingAccumulator> accumulators) {
    }
}
