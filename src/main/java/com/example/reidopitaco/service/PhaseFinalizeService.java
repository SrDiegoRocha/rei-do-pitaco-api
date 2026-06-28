package com.example.reidopitaco.service;

import com.example.reidopitaco.dto.response.StandingsResponse;
import com.example.reidopitaco.entity.PhaseGroup;
import com.example.reidopitaco.entity.PhaseTeam;
import com.example.reidopitaco.entity.Team;
import com.example.reidopitaco.entity.Tournament;
import com.example.reidopitaco.entity.TournamentPhase;
import com.example.reidopitaco.entity.TournamentZone;
import com.example.reidopitaco.enums.MatchStatus;
import com.example.reidopitaco.enums.TiebreakCriteria;
import com.example.reidopitaco.enums.TournamentPhaseType;
import com.example.reidopitaco.enums.ZoneSelectionMode;
import com.example.reidopitaco.exception.NotTournamentOwnerException;
import com.example.reidopitaco.exception.PhaseFinalizeException;
import com.example.reidopitaco.exception.PhaseNotFoundException;
import com.example.reidopitaco.exception.TournamentNotFoundException;
import com.example.reidopitaco.repository.MatchRepository;
import com.example.reidopitaco.repository.PhaseGroupRepository;
import com.example.reidopitaco.repository.PhaseTeamRepository;
import com.example.reidopitaco.repository.TeamRepository;
import com.example.reidopitaco.repository.TournamentPhaseRepository;
import com.example.reidopitaco.repository.TournamentRepository;
import com.example.reidopitaco.repository.TournamentZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PhaseFinalizeService {

    private final TournamentRepository tournamentRepository;
    private final TournamentPhaseRepository phaseRepository;
    private final PhaseGroupRepository groupRepository;
    private final PhaseTeamRepository phaseTeamRepository;
    private final TournamentZoneRepository zoneRepository;
    private final MatchRepository matchRepository;
    private final TeamRepository teamRepository;
    private final StandingsService standingsService;

    public PhaseFinalizeService(
            TournamentRepository tournamentRepository,
            TournamentPhaseRepository phaseRepository,
            PhaseGroupRepository groupRepository,
            PhaseTeamRepository phaseTeamRepository,
            TournamentZoneRepository zoneRepository,
            MatchRepository matchRepository,
            TeamRepository teamRepository,
            StandingsService standingsService
    ) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.groupRepository = groupRepository;
        this.phaseTeamRepository = phaseTeamRepository;
        this.zoneRepository = zoneRepository;
        this.matchRepository = matchRepository;
        this.teamRepository = teamRepository;
        this.standingsService = standingsService;
    }

    @Transactional
    public StandingsResponse finalize(UUID ownerPublicId, UUID tournamentPublicId, UUID phasePublicId) {
        Tournament tournament = tournamentRepository.findByPublicIdAndActiveTrue(tournamentPublicId)
                .orElseThrow(TournamentNotFoundException::new);
        if (!tournament.getOwner().getPublicId().equals(ownerPublicId)) {
            throw new NotTournamentOwnerException();
        }
        TournamentPhase phase = phaseRepository
                .findByPublicIdAndTournamentPublicId(phasePublicId, tournamentPublicId)
                .orElseThrow(PhaseNotFoundException::new);

        ensureAllMatchesResolved(phase);

        StandingsResponse standings = standingsService.computeFor(tournament, phasePublicId);
        List<TournamentZone> zones = zoneRepository.findAllByPhaseIdOrderByPositionAsc(phase.getId());

        Set<UUID> alreadySeededPhases = new HashSet<>();

        for (TournamentZone zone : zones) {
            if (zone.getNextPhase() == null) {
                continue;
            }
            UUID nextPhaseId = zone.getNextPhase().getPublicId();
            if (phaseTeamRepository.countByPhaseId(zone.getNextPhase().getId()) > 0
                    && !alreadySeededPhases.contains(nextPhaseId)) {
                throw new PhaseFinalizeException(
                        "Next phase '" + zone.getNextPhase().getName() + "' already has teams; cannot finalize"
                );
            }
            alreadySeededPhases.add(nextPhaseId);

            List<UUID> teamsToPromote = zone.getSelectionMode() == ZoneSelectionMode.ALL
                    ? collectAllInRange(standings, zone)
                    : collectBestRanked(standings, zone, tournament);

            promoteTeams(zone.getNextPhase(), teamsToPromote);
        }

        phase.setFinalizedAt(Instant.now());
        phaseRepository.save(phase);

        return standings;
    }

    private void ensureAllMatchesResolved(TournamentPhase phase) {
        long scheduled = matchRepository.countByPhaseIdAndStatus(phase.getId(), MatchStatus.SCHEDULED);
        if (scheduled > 0) {
            throw new PhaseFinalizeException(
                    "Phase has " + scheduled + " unfinished matches (SCHEDULED); finish or cancel them first"
            );
        }
        long total = matchRepository.countByPhaseId(phase.getId());
        if (total == 0) {
            throw new PhaseFinalizeException("Phase has no matches to finalize");
        }
    }

    private List<UUID> collectAllInRange(StandingsResponse standings, TournamentZone zone) {
        List<UUID> result = new ArrayList<>();
        for (StandingsResponse.GroupStandings group : standings.groups()) {
            for (StandingsResponse.StandingRow row : group.rows()) {
                if (row.position() >= zone.getFromPosition() && row.position() <= zone.getToPosition()) {
                    result.add(row.teamId());
                }
            }
        }
        return result;
    }

    private List<UUID> collectBestRanked(StandingsResponse standings, TournamentZone zone, Tournament tournament) {
        int targetPosition = zone.getFromPosition();
        int needed = zone.getBestRankedCount() == null ? 0 : zone.getBestRankedCount();

        List<StandingsResponse.StandingRow> candidates = new ArrayList<>();
        for (StandingsResponse.GroupStandings group : standings.groups()) {
            for (StandingsResponse.StandingRow row : group.rows()) {
                if (row.position() == targetPosition) {
                    candidates.add(row);
                }
            }
        }

        // Ranqueia pelos critérios de desempate configurados no torneio — mesma fonte de verdade da
        // projeção `qualifies` do StandingsService, para que finalize e standings nunca divirjam.
        List<TiebreakCriteria> tiebreaks = tournament.getTiebreakCriteria().stream()
                .sorted(Comparator.comparingInt(c -> c.getPosition()))
                .map(c -> c.getCriteria())
                .toList();
        candidates.sort(StandingsService.bestRankedComparator(
                tiebreaks,
                StandingsResponse.StandingRow::points,
                StandingsResponse.StandingRow::wins,
                StandingsResponse.StandingRow::goalDifference,
                StandingsResponse.StandingRow::goalsFor,
                StandingsResponse.StandingRow::losses,
                StandingsResponse.StandingRow::teamName
        ));

        return candidates.stream()
                .limit(needed)
                .map(StandingsResponse.StandingRow::teamId)
                .toList();
    }

    private void promoteTeams(TournamentPhase nextPhase, List<UUID> teamPublicIds) {
        if (teamPublicIds.isEmpty()) {
            return;
        }
        // Resolve por publicId sem escopar por dono — o time pode ser do dono OU do sistema (sem dono).
        Map<UUID, Team> teamsByPublicId = new HashMap<>();
        for (UUID pid : teamPublicIds) {
            Team team = teamRepository.findByPublicIdAndActiveTrue(pid)
                    .orElseThrow(() -> new PhaseFinalizeException("Team " + pid + " could not be resolved"));
            teamsByPublicId.put(pid, team);
        }
        for (UUID pid : teamPublicIds) {
            Team team = teamsByPublicId.get(pid);
            PhaseTeam pt = PhaseTeam.builder()
                    .phase(nextPhase)
                    .team(team)
                    .build();
            phaseTeamRepository.save(pt);
        }
    }
}
