package com.example.reidopitaco.dto.response;

import com.example.reidopitaco.enums.MatchStatus;
import com.example.reidopitaco.enums.MatchType;
import com.example.reidopitaco.enums.TeamType;

import java.time.Instant;
import java.util.UUID;

public record MatchResponse(
        UUID id,
        UUID phaseId,
        UUID groupId,
        String groupName,
        int round,
        UUID tieId,
        MatchType matchType,
        TeamRef homeTeam,
        TeamRef awayTeam,
        Instant scheduledAt,
        Integer homeScore,
        Integer awayScore,
        Integer homeExtraTimeScore,   // placar da prorrogação (KO jogo único); null se não houve
        Integer awayExtraTimeScore,
        Integer homePenalties,
        Integer awayPenalties,
        // Apoio ao palpite de pênaltis (ver # Sistema de palpites):
        boolean penaltyShootoutEligible,  // empate aqui pode ir aos pênaltis (jogo único KO ou perna de volta)
        int aggregateBeforeHome,          // gols do mandante DESTA partida nas pernas anteriores (orientado a esta partida)
        int aggregateBeforeAway,          // idem para o visitante DESTA partida; 0 em jogo único / ida não concluída
        MatchStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public record TeamRef(
            UUID id,
            String name,
            String shortName,
            String badgeUrl,
            String primaryColor,
            String secondaryColor,
            TeamType teamType,       // CLUB | NATIONAL_TEAM
            String countryCode       // flagicons (ex. "br"); preenchido nas seleções, senão null
    ) {
    }
}
