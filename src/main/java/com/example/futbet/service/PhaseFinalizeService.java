package com.example.futbet.service;

import com.example.futbet.dto.response.StandingsResponse;
import com.example.futbet.entity.PhaseGroup;
import com.example.futbet.entity.PhaseTeam;
import com.example.futbet.entity.Team;
import com.example.futbet.entity.Tournament;
import com.example.futbet.entity.TournamentPhase;
import com.example.futbet.entity.TournamentZone;
import com.example.futbet.enums.MatchStatus;
import com.example.futbet.enums.TournamentPhaseType;
import com.example.futbet.enums.ZoneSelectionMode;
import com.example.futbet.exception.PhaseFinalizeException;
import com.example.futbet.exception.PhaseNotFoundException;
import com.example.futbet.exception.TournamentNotFoundException;
import com.example.futbet.repository.MatchRepository;
import com.example.futbet.repository.PhaseGroupRepository;
import com.example.futbet.repository.PhaseTeamRepository;
import com.example.futbet.repository.TeamRepository;
import com.example.futbet.repository.TournamentPhaseRepository;
import com.example.futbet.repository.TournamentRepository;
import com.example.futbet.repository.TournamentZoneRepository;
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
    public StandingsResponse finalize(UUID tournamentPublicId, UUID phasePublicId) {
        Tournament tournament = tournamentRepository.findByPublicIdAndActiveTrue(tournamentPublicId)
                .orElseThrow(TournamentNotFoundException::new);
        TournamentPhase phase = phaseRepository
                .findByPublicIdAndTournamentPublicId(phasePublicId, tournamentPublicId)
                .orElseThrow(PhaseNotFoundException::new);

        ensureAllMatchesResolved(phase);

        StandingsResponse standings = standingsService.compute(tournamentPublicId, phasePublicId);
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
                    : collectBestRanked(standings, zone);

            promoteTeams(zone.getNextPhase(), teamsToPromote);
        }

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

    private List<UUID> collectBestRanked(StandingsResponse standings, TournamentZone zone) {
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

        candidates.sort(Comparator
                .comparingInt(StandingsResponse.StandingRow::points).reversed()
                .thenComparing(Comparator.comparingInt(StandingsResponse.StandingRow::wins).reversed())
                .thenComparing(Comparator.comparingInt(StandingsResponse.StandingRow::goalDifference).reversed())
                .thenComparing(Comparator.comparingInt(StandingsResponse.StandingRow::goalsFor).reversed())
                .thenComparing(Comparator.comparingInt(StandingsResponse.StandingRow::losses))
                .thenComparing((a, b) -> a.teamName().compareToIgnoreCase(b.teamName()))
        );

        return candidates.stream()
                .limit(needed)
                .map(StandingsResponse.StandingRow::teamId)
                .toList();
    }

    private void promoteTeams(TournamentPhase nextPhase, List<UUID> teamPublicIds) {
        if (teamPublicIds.isEmpty()) {
            return;
        }
        Map<UUID, Team> teamsByPublicId = new HashMap<>();
        for (UUID pid : teamPublicIds) {
            Team team = teamRepository.findByPublicIdAndOwnerPublicIdAndActiveTrue(
                            pid, nextPhase.getTournament().getOwner().getPublicId()
                    )
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
