package com.example.reidopitaco.dto.response;

import com.example.reidopitaco.enums.TeamType;

import java.time.Instant;
import java.util.UUID;

public record TeamResponse(
        UUID id,
        String name,
        String shortName,
        String badgeUrl,
        String primaryColor,
        String secondaryColor,
        boolean system,          // true = time padrão do sistema (não editável/deletável)
        TeamType teamType,       // CLUB ou NATIONAL_TEAM
        String countryCode,      // código flagicons (ex.: "br", "gb-eng"); seleções e clubes do sistema
        String leagueSlug,       // liga do clube do sistema (ex.: "brasileirao-serie-a"); null nos demais
        String leagueName,       // nome de exibição da liga (ex.: "Brasileirão Série A")
        Instant createdAt,
        Instant updatedAt
) {
}
