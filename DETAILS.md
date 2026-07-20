# Feature: Aba "Retrospecto" no detalhe da partida

Documento para o **backend**. Descreve a nova aba de retrospecto/análise que foi montada no front, **tudo que ela precisa da API** (1 endpoint novo agregado), as regras de cálculo de cada campo, casos de borda, decisões em aberto e sugestões de evolução.

Todos os campos JSON são **`camelCase`**. Escopo, controle de acesso e formato de erro seguem os mesmos padrões das demais rotas (ver `API.md`).

---

## 1. Visão geral

Nova aba **"Retrospecto"** na tela de detalhe da partida (`/tournaments/{tid}/phases/{pid}/matches/{mid}`), ao lado de "Pitacos" e "Detalhes". Serve para o palpiteiro analisar o jogo antes de dar o pitaco. Mostra:

1. **Contexto na competição** — posição de cada time, com a regra dependendo do tipo de fase:
   - **Pontos corridos (`ROUND_ROBIN`)** → posição atual na tabela da fase.
   - **Fase de grupos (`GROUPS`)** → posição atual dentro do grupo de cada time.
   - **Mata-mata (`KNOCKOUT`)** → **se a fase anterior foi `ROUND_ROBIN` ou `GROUPS`**, a posição final em que cada time terminou nessa última fase. Se a fase anterior também for mata-mata (ou não existir), sem contexto (`NONE`).
2. **Confronto direto no torneio** — agregado (vitórias/empates/vitórias + gols) e os **últimos 2 confrontos** entre os dois times **dentro do torneio**, se já se enfrentaram.
3. **Gols esperados na partida** — projeção de placar (ataque × defesa da forma recente). ⚠️ Não é xG de finalização (ver §4.13).
4. **Comparativo "quem chega melhor"** — barra dupla dos dois times (aproveitamento, gols marcados/jogo, gols sofridos/jogo).
5. **Últimos jogos de cada time** (até **10**) — forma (V/E/D), gols marcados e sofridos, e a lista dos jogos.
6. **Estatísticas agregadas** dos últimos 10 — aproveitamento, médias de gols pró/contra, saldo, jogos sem sofrer/sem marcar, **tendência de gols (over 2.5 e ambas marcam)**, sequência atual, recorte mandante/visitante e **dias de descanso**.
7. **Como a galera se sai** — desempenho dos palpiteiros com cada time no torneio (placar exato %, acerto de desfecho %, média de pontos, fator surpresa).

O carregamento é **sob demanda**: o front só chama o endpoint na primeira vez que o usuário abre a aba (economiza request em quem nunca abre).

**Fonte única de verdade:** todos os números são calculados **na API** — o front só apresenta. Não há nenhuma derivação de dado no cliente.

---

## 2. O que já foi feito no front (para referência)

| Arquivo | Papel |
|---|---|
| `src/app/core/interfaces/match-analysis.interface.ts` | **Contrato TS** (o response abaixo). É a fonte da verdade — o backend deve casar 1:1 com isto. |
| `src/app/core/services/match-analysis.service.ts` | `MatchAnalysisService.get(tid, mid)` → `GET /api/tournaments/{tid}/matches/{mid}/analysis`. |
| `src/app/pages/match-detail/match-analysis/` | Componente `app-match-analysis` (ts/html/scss) que renderiza a aba. Puramente apresentacional. |
| `src/app/pages/match-detail/match-detail.component.*` | Aba "Retrospecto" adicionada (tab, lazy-load, estados de loading/erro, swipe). |

O front já trata **loading**, **erro** (com botão "tentar novamente") e **404/indisponível** (estado vazio amigável) — então pode subir o endpoint depois sem quebrar a tela.

---

## 3. Endpoint necessário

### `GET /api/tournaments/{tid}/matches/{mid}/analysis` → 200 `MatchAnalysisResponse`

- **Path**: sem `phaseId` (a partida já resolve a fase internamente), igual às rotas de palpite (`/api/tournaments/{tid}/matches/{mid}/predictions`).
- **Acesso**: mesmo controle de visibilidade das outras rotas do torneio (owner, member `ACTIVE`, ou `PUBLIC` não-`DRAFT`); senão **404**. É seguro liberar para todos os que já veem a partida — não expõe pitacos de ninguém.
- **404** se a partida não existe no torneio.
- **Sem paginação.** Um único objeto agregado (é uma tela mobile — evitar N chamadas).

### Response — `MatchAnalysisResponse`

```ts
export interface MatchAnalysisResponse {
  matchId: string;
  phaseType: 'ROUND_ROBIN' | 'KNOCKOUT' | 'GROUPS';   // tipo da fase DESTA partida
  home: TeamFormSummary;      // time mandante DESTA partida
  away: TeamFormSummary;      // time visitante DESTA partida
  headToHead: HeadToHead;
  expectedGoals: ExpectedGoals | null;  // projeção de gols da partida (ver §4.13); null se algum time sem jogos
  recentWindow: number;       // tamanho da janela de "últimos jogos" (use 10)
  headToHeadWindow: number;   // janela de confrontos diretos (use 2)
}

export interface ExpectedGoals {   // ver §4.13 — projeção pela forma, NÃO é xG de finalização
  home: number;               // gols esperados do mandante nesta partida (1 casa decimal ok)
  away: number;               // gols esperados do visitante
}

export interface TeamFormSummary {
  team: TeamRef;                       // mesmo TeamRef do MatchResponse (com cores/countryCode)
  standing: TeamStandingContext | null; // null quando o contexto seria NONE
  recentMatches: TeamFormMatch[];      // mais recente → mais antigo, até recentWindow
  stats: TeamFormStats;
  predictors: TeamPredictorStats;      // desempenho dos palpiteiros com este time (ver §4.11)
  restDays: number | null;             // dias do último jogo até esta partida (ver §4.12); null se indeterminado
}

export interface TeamStandingContext {
  kind: 'ROUND_ROBIN' | 'GROUP' | 'PREVIOUS_PHASE' | 'NONE';
  position: number;        // 1-indexed
  totalTeams: number;      // total na tabela/grupo (para "3º de 8")
  points: number | null;   // pontos na tabela referenciada; null quando não se aplica
  played: number | null;   // jogos na tabela referenciada; null quando não se aplica
  groupName: string | null;// preenchido só quando kind === 'GROUP'
  phaseId: string;         // fase a que a posição se refere (a atual, ou a anterior no KO)
  phaseName: string;
}

export interface TeamFormMatch {
  matchId: string;
  phaseId: string;
  phaseName: string;
  round: number;
  scheduledAt: string | null;   // ISO instant
  opponent: TeamRef;            // adversário naquele jogo
  playedHome: boolean;          // o "dono" do card jogou como mandante?
  goalsFor: number;             // gols do dono (placar DECISIVO: prorrogação se houve, senão 90')
  goalsAgainst: number;         // gols sofridos (mesma base)
  outcome: 'W' | 'D' | 'L';     // resultado pela ótica do dono, pelo placar decisivo (ver §4.4)
  hadExtraTime: boolean;
  penaltyFor: number | null;    // pênaltis marcados/sofridos SE decidido nos pênaltis; senão null
  penaltyAgainst: number | null;
  advanced: boolean | null;     // foi a pênaltis? o dono avançou? null quando não houve pênaltis
}

export interface TeamFormStats {
  played: number;               // nº de jogos na janela (pode ser < recentWindow)
  wins: number;
  draws: number;
  losses: number;
  goalsFor: number;             // soma na janela
  goalsAgainst: number;
  goalDifference: number;       // goalsFor - goalsAgainst
  cleanSheets: number;          // jogos sem sofrer gol
  failedToScore: number;        // jogos sem marcar
  overTwoFive: number;          // jogos com 3+ gols no total (over 2.5) — ver §4.5
  bothTeamsScored: number;      // jogos em que os dois marcaram (BTTS) — ver §4.5
  points: number;               // pontos "virtuais" 3/1/0 na janela (base do aproveitamento)
  performancePct: number | null;// round(points / (played*3) * 100); null se played=0
  streak: FormStreak | null;    // sequência atual; null se played=0
  homeRecord: VenueRecord | null; // recorte por mando (v1); null só se não houver jogos em casa na janela
  awayRecord: VenueRecord | null; // idem, como visitante
}

export interface TeamPredictorStats {   // ver §4.11
  ratedMatches: number;         // jogos COMPLETED do time no torneio com ≥1 pitaco
  totalPredictions: number;     // total de pitacos considerados nesses jogos
  exactScoreRate: number | null;    // % de pitacos com placar exato (0-100); null se totalPredictions=0
  correctOutcomeRate: number | null;// % de pitacos que acertaram o desfecho; null se totalPredictions=0
  averagePoints: number | null;     // média de pontos por pitaco; null se totalPredictions=0
  upsetRate: number | null;         // % de jogos em que a maioria errou o desfecho; null se ratedMatches=0
}

export interface FormStreak {
  type: 'WIN' | 'LOSS' | 'DRAW' | 'UNBEATEN' | 'WINLESS';
  count: number;                // >= 1
}

export interface VenueRecord {
  played: number;
  wins: number;
  draws: number;
  losses: number;
  goalsFor: number;
  goalsAgainst: number;
}

export interface HeadToHead {
  totalMeetings: number;        // confrontos anteriores entre os dois NO torneio (0 se nunca)
  homeTeamWins: number;         // contagens pela ótica dos times DESTA partida...
  draws: number;
  awayTeamWins: number;         // ...ou seja, "home" = mandante desta partida em qualquer confronto
  homeTeamGoals: number;        // gols somados do mandante-desta-partida nos confrontos
  awayTeamGoals: number;
  averageGoals: number | null;  // (homeTeamGoals + awayTeamGoals) / totalMeetings; null se totalMeetings=0
  recentMeetings: HeadToHeadMatch[]; // mais recente → antigo, até headToHeadWindow
}

export interface HeadToHeadMatch {
  matchId: string;
  phaseId: string;
  phaseName: string;
  round: number;
  scheduledAt: string | null;
  homeTeam: TeamRef;            // times como jogaram NAQUELE confronto (mando pode estar invertido)
  awayTeam: TeamRef;
  homeGoals: number;            // placar decisivo daquele confronto
  awayGoals: number;
  hadExtraTime: boolean;
  penaltyHomeGoals: number | null;
  penaltyAwayGoals: number | null;
}
```

---

## 4. Regras de cálculo (detalhe por campo)

### 4.1 Escopo dos dados — ✅ DEFINIDO: por torneio
**"Últimos jogos" e "confronto direto" consideram SOMENTE partidas deste torneio.** (Decidido — não é histórico global do time.) É o que faz sentido pro pitaco (forma na competição) e casa com o resto da tela. A query do backend deve filtrar pelo `tournamentId`.

### 4.2 Quais partidas contam
- Só partidas **`COMPLETED`** (com resultado lançado). `SCHEDULED`/`CANCELLED` são ignoradas.
- **Não** incluir a própria partida atual (ela ainda não aconteceu, ou se aconteceu não é "retrospecto").
- Ordenar por `scheduledAt` desc; para partidas sem `scheduledAt`, cair para `createdAt`/`round` desc como desempate estável.
- Pegar as primeiras `recentWindow` (10). `played` em `stats` reflete quantas realmente entraram (pode ser < 10).

### 4.3 Placar "decisivo" (`goalsFor`/`goalsAgainst`, `homeGoals`/`awayGoals`)
Mesma regra que o front já usa em `matchDisplayScore`: **se houve prorrogação, usar o placar da prorrogação** (que é cumulativo, já inclui os 90'); senão o placar do tempo normal. **Pênaltis não entram** em `goalsFor`/`goalsAgainst` — vão separados em `penaltyFor`/`penaltyAgainst`.

### 4.4 `outcome` (V/E/D pela ótica do time)
Pelo **placar decisivo** (§4.3):
- `goalsFor > goalsAgainst` → `W`; `<` → `L`; `==` → `D`.
- Empate que foi para os pênaltis conta como **`D`** (empate no tempo/prorrogação). Quem avançou nos pênaltis é sinalizado à parte por `advanced` — o front mostra "pen ✓/✗", mas a forma (dot) permanece amarela/empate. Isso é padrão em apps de futebol e mantém `stats.points` coerente (empate = 1 ponto virtual).

### 4.5 `stats` (agregados da janela)
- `points` = `wins*3 + draws*1` (fixo 3/1/0 para o aproveitamento; **não** usar `winPoints/drawPoints` do torneio aqui — é aproveitamento esportivo, não pontuação de pitaco).
- `performancePct` = `round(points / (played*3) * 100)`, ou `null` se `played == 0`.
- `cleanSheets` = jogos com `goalsAgainst == 0`. `failedToScore` = jogos com `goalsFor == 0`.
- `goalDifference` = `goalsFor - goalsAgainst`.
- `overTwoFive` = nº de jogos da janela com `goalsFor + goalsAgainst >= 3` (over 2.5 gols, pelo placar decisivo §4.3).
- `bothTeamsScored` = nº de jogos da janela com `goalsFor > 0 && goalsAgainst > 0` (ambas marcam / BTTS).
- **Todos** os contadores usam a **mesma janela** de `recentMatches` (o mesmo `played`), para o front exibir "X/played".

### 4.6 `streak` (sequência atual)
Calcular a partir do jogo mais recente para trás:
- Se os últimos N resultados são todos `W` → `{type:'WIN', count:N}`. Idem `L`/`D`.
- Preferir a sequência **mais informativa**: se há mistura de V e E sem derrota, use `UNBEATEN` (invicto). Se mistura de D e E sem vitória, use `WINLESS` (sem vencer). Sugestão de prioridade: `WIN`/`LOSS`/`DRAW` puros primeiro; se não puro, `UNBEATEN` (nenhuma derrota na sequência) ou `WINLESS` (nenhuma vitória).
- `null` se `played == 0`.
- O front já rotula em pt-BR (ex.: "3 vitórias seguidas", "5 jogos invicto") a partir de `type` + `count`.

### 4.7 `homeRecord`/`awayRecord` (recorte por mando) — v1
- `homeRecord` = agregado dos jogos **da janela** (§4.2) em que o time jogou **como mandante**; `awayRecord` como visitante. Mesmas contagens de `VenueRecord` (calculadas pelo placar decisivo §4.3).
- **Entra na v1** (confirmado). Só mande `null` num deles se o time **não teve nenhum jogo** naquele mando dentro da janela (ex.: jogou as 10 fora → `homeRecord = null`). O front esconde o bloco quando ambos são `null`.
- `homeRecord.played + awayRecord.played` deve bater com `stats.played`.

### 4.8 Contexto posicional (`standing`)
Depende do `phaseType` da partida atual:

| Fase da partida | `kind` | O que calcular |
|---|---|---|
| `ROUND_ROBIN` | `ROUND_ROBIN` | Posição atual na tabela da fase (reaproveitar o cálculo de `StandingsResponse`). `points`/`played` preenchidos. `groupName = null`. |
| `GROUPS` | `GROUP` | Posição atual **dentro do grupo** do time. `groupName` preenchido. `points`/`played` do grupo. |
| `KNOCKOUT` **e** fase anterior é `ROUND_ROBIN`/`GROUPS` | `PREVIOUS_PHASE` | Posição **final** do time naquela fase anterior. `phaseId`/`phaseName` = da fase anterior. Em `GROUPS`, dá pra preencher `groupName`; `points`/`played` opcionais (podem vir null). |
| `KNOCKOUT` e fase anterior é `KNOCKOUT` (ou não há anterior) | — | Mandar `standing: null` (front trata como "sem posição"). |

- `totalTeams` = nº de times na tabela/grupo referenciado.
- Se os dois times estão no mesmo grupo/tabela, cada um traz sua própria posição.
- "Fase anterior" = a fase de `position` imediatamente menor no torneio (a que alimenta o mata-mata). Se houver mais de uma liga/grupos antes, usar a **imediatamente anterior**.
- Reaproveitar a lógica de ordenação/tiebreak que já existe em `/standings` para não divergir das tabelas.

### 4.9 `headToHead` (confronto direto)
- Considera confrontos anteriores entre os **mesmos dois times** no torneio (§4.1), `COMPLETED`, **exceto** a partida atual.
- `homeTeamWins`/`awayTeamWins`/`homeTeamGoals`/`awayTeamGoals` são **orientados aos times DESTA partida** (mandante desta partida = "home"), independentemente de quem foi mandante em cada confronto passado. `draws` conta empates no placar decisivo (inclui os que foram a pênaltis).
- `recentMeetings`: os últimos `headToHeadWindow` (2), com os times **como jogaram naquele confronto** (`homeTeam`/`awayTeam` daquele jogo — mando pode estar invertido em relação à partida atual). Placar decisivo + pênaltis quando houver.
- `averageGoals` = `(homeTeamGoals + awayTeamGoals) / totalMeetings` (número com casas decimais; o front formata para 1 casa). `null` quando `totalMeetings == 0`.
- `totalMeetings == 0` é normal (nunca se enfrentaram) → front mostra "ainda não se enfrentaram".

### 4.10 Símbolo do infinito
O front já aplica o pipe de placar (99 gols → "∞"). O backend segue mandando o número cru (`99`); a formatação é do front.

### 4.11 `predictors` (desempenho dos palpiteiros) — v1
"Como a galera se sai com este time." Calculado sobre **todos os jogos `COMPLETED` do time no torneio** (não só a janela de 10) que tiveram **ao menos 1 pitaco**. Um objeto por time (`home.predictors` / `away.predictors`).

- `ratedMatches` = nº desses jogos (COMPLETED do time, com ≥1 pitaco).
- `totalPredictions` = soma de todos os pitacos desses jogos.
- `exactScoreRate` = `100 * (pitacos com placar exato) / totalPredictions`. "Placar exato" = acertou o placar do **tempo normal** (`homeScore`/`awayScore` do pitaco == resultado dos 90'), a mesma base do componente de pontuação "placar exato" (ver `API.md` §19). `null` se `totalPredictions == 0`.
- `correctOutcomeRate` = `100 * (pitacos que acertaram o desfecho) / totalPredictions`. "Desfecho" = vencedor ou empate, considerando a prorrogação quando o palpiteiro a informou (mesma regra da distribuição em `/predictions/stats`). `null` se `totalPredictions == 0`.
- `averagePoints` = média de `points` dos pitacos desses jogos (usa a pontuação real já gravada no pitaco). `null` se `totalPredictions == 0`.
- `upsetRate` ("fator surpresa") = `100 * (jogos em que a MAIORIA dos pitacos errou o desfecho) / ratedMatches`. Por jogo: se `< 50%` dos pitacos acertaram o desfecho, conta como "surpresa". `null` se `ratedMatches == 0`.
- Percentuais são **0–100** (o front arredonda pra inteiro). Escopo: **este torneio** (§4.1).
- Se um time não tem nenhum jogo avaliado, mande o objeto com `ratedMatches: 0`, `totalPredictions: 0` e as taxas `null` — o front mostra "—". O card só aparece quando **ao menos um** dos dois times tem `ratedMatches > 0`.

### 4.12 `restDays` (descanso)
- Dias inteiros entre o **jogo mais recente** do time (o `recentMatches[0].scheduledAt`, considerando só jogos com horário) e o `scheduledAt` **desta** partida. Se esta partida não tem `scheduledAt`, usar "agora" (instante da requisição) como referência.
- `floor((referência − últimoJogo) / 1 dia)`. Nunca negativo — se der negativo (dados inconsistentes) ou não houver jogo anterior com horário, mande `null`.
- O front exibe "jogou hoje" (0), "Nd de descanso" (>0) ou esconde (`null`).

### 4.13 `expectedGoals` (gols esperados na partida) — v1
⚠️ **Não é xG de finalização.** A base não tem dados de chute (posição/tipo de finalização), então xG "real" é impossível. Isto é uma **projeção pela forma**: cruza o ataque de cada time com a defesa do adversário, usando as médias da janela de `recentMatches`.

Modelo (simples e determinístico):
```
homeGF = home.stats.goalsFor / home.stats.played        // ataque do mandante
homeGA = home.stats.goalsAgainst / home.stats.played     // defesa do mandante
awayGF = away.stats.goalsFor / away.stats.played
awayGA = away.stats.goalsAgainst / away.stats.played

expectedGoals.home = (homeGF + awayGA) / 2   // ataque do mandante × defesa do visitante
expectedGoals.away = (awayGF + homeGA) / 2   // ataque do visitante × defesa do mandante
```
- Arredondar para **1 casa decimal** (o front formata; pode mandar cru com mais casas que o front trunca).
- **`null`** quando **qualquer** um dos times tem `stats.played == 0` (sem forma pra projetar).
- Se quiser refinar depois (peso de mando de campo, normalização pela média de gols do torneio, Poisson pra probabilidade de placar), o campo não muda de forma — só o cálculo. Por ora, a média simples acima basta.

---

## 5. Exemplos de payload

### 5.1 Pontos corridos, com confronto direto e forma
```jsonc
{
  "matchId": "m-123",
  "phaseType": "ROUND_ROBIN",
  "recentWindow": 10,
  "headToHeadWindow": 2,
  "expectedGoals": { "home": 1.9, "away": 1.1 },
  "home": {
    "team": { "id": "t-fla", "name": "Flamengo", "shortName": "FLA", "badgeUrl": null,
              "primaryColor": "#E11D2A", "secondaryColor": "#111", "teamType": "CLUB", "countryCode": null },
    "standing": { "kind": "ROUND_ROBIN", "position": 2, "totalTeams": 20,
                  "points": 41, "played": 19, "groupName": null,
                  "phaseId": "p-1", "phaseName": "Pontos Corridos" },
    "recentMatches": [
      { "matchId": "m-98", "phaseId": "p-1", "phaseName": "Pontos Corridos", "round": 19,
        "scheduledAt": "2026-06-30T22:00:00Z", "opponent": { "id": "t-pal", "name": "Palmeiras",
        "shortName": "PAL", "badgeUrl": null, "primaryColor": "#0A7", "secondaryColor": null,
        "teamType": "CLUB", "countryCode": null }, "playedHome": true,
        "goalsFor": 3, "goalsAgainst": 1, "outcome": "W", "hadExtraTime": false,
        "penaltyFor": null, "penaltyAgainst": null, "advanced": null }
      // ... até 10
    ],
    "stats": { "played": 10, "wins": 6, "draws": 2, "losses": 2, "goalsFor": 18, "goalsAgainst": 9,
               "goalDifference": 9, "cleanSheets": 4, "failedToScore": 1,
               "overTwoFive": 6, "bothTeamsScored": 5, "points": 20,
               "performancePct": 67, "streak": { "type": "WIN", "count": 3 },
               "homeRecord": { "played": 5, "wins": 4, "draws": 1, "losses": 0, "goalsFor": 11, "goalsAgainst": 3 },
               "awayRecord": { "played": 5, "wins": 2, "draws": 1, "losses": 2, "goalsFor": 7, "goalsAgainst": 6 } },
    "predictors": { "ratedMatches": 12, "totalPredictions": 340, "exactScoreRate": 18,
                    "correctOutcomeRate": 61, "averagePoints": 2.4, "upsetRate": 25 },
    "restDays": 4
  },
  "away": { "...": "mesma forma (stats + predictors + restDays)" },
  "headToHead": {
    "totalMeetings": 3, "homeTeamWins": 1, "draws": 1, "awayTeamWins": 1, "averageGoals": 3.0,
    "homeTeamGoals": 5, "awayTeamGoals": 4,
    "recentMeetings": [
      { "matchId": "m-77", "phaseId": "p-1", "phaseName": "Pontos Corridos", "round": 8,
        "scheduledAt": "2026-04-10T22:00:00Z",
        "homeTeam": { "id": "t-cor", "name": "Corinthians", "shortName": "COR", "...": "..." },
        "awayTeam": { "id": "t-fla", "name": "Flamengo", "shortName": "FLA", "...": "..." },
        "homeGoals": 2, "awayGoals": 2, "hadExtraTime": false,
        "penaltyHomeGoals": null, "penaltyAwayGoals": null }
    ]
  }
}
```

### 5.2 Mata-mata que veio de fase de grupos
```jsonc
{
  "matchId": "m-500",
  "phaseType": "KNOCKOUT",
  "recentWindow": 10, "headToHeadWindow": 2,
  "expectedGoals": { "home": 1.4, "away": 0.9 },
  "home": {
    "team": { "id": "t-bra", "name": "Brasil", "shortName": "BRA", "teamType": "NATIONAL_TEAM", "countryCode": "br", "...": "..." },
    "standing": { "kind": "PREVIOUS_PHASE", "position": 1, "totalTeams": 4,
                  "points": 9, "played": 3, "groupName": "A",
                  "phaseId": "p-groups", "phaseName": "Fase de Grupos" },
    "recentMatches": [ /* ... */ ],
    "stats": { "...": "..." },
    "predictors": { "ratedMatches": 0, "totalPredictions": 0, "exactScoreRate": null,
                    "correctOutcomeRate": null, "averagePoints": null, "upsetRate": null },
    "restDays": 3
  },
  "away": { "...": "..." },
  "headToHead": { "totalMeetings": 0, "homeTeamWins": 0, "draws": 0, "awayTeamWins": 0,
                  "homeTeamGoals": 0, "awayTeamGoals": 0, "averageGoals": null, "recentMeetings": [] }
}
```

---

## 6. Decisões (todas fechadas)

Todas resolvidas — **nada pendente de confirmação**. Registradas aqui só pra contexto:

1. ✅ **Escopo: por torneio.** `recentMatches`, `headToHead` e `predictors` filtram por `tournamentId`.
2. ✅ **`homeRecord`/`awayRecord` entram na v1** (ver §4.7).
3. ✅ **Janelas fixas:** `recentWindow = 10`, `headToHeadWindow = 2` (constantes; o response ecoa os valores). Não precisa query param.
4. ✅ **Contexto posicional em mata-mata vindo de outro mata-mata** (ou sem fase anterior) = `standing: null` (`NONE`).
5. ✅ **Fonte única de verdade no backend.** Todos os agregados são **computados na API** — o front não deriva mais nada (over 2.5, BTTS, média do confronto, descanso e desempenho dos palpiteiros viraram campos do contrato).

---

## 7. Extras — todos no contrato (computados na API)

A pedido, **nada é derivado no front**; tudo vem pronto no response acima:

- ✅ **Tendência de gols** → `stats.overTwoFive` e `stats.bothTeamsScored` (§4.5). O front mostra "X/played".
- ✅ **Média de gols do confronto** → `headToHead.averageGoals` (§4.9).
- ✅ **Descanso** → `restDays` por time (§4.12).
- ✅ **Desempenho dos palpiteiros** → `predictors` por time (§4.11): placar exato %, desfecho %, média de pontos e fator surpresa. Card "Como a galera se sai" com os dois times lado a lado.
- ✅ **Gols esperados na partida** → `expectedGoals` (§4.13). Projeção pela forma (ataque × defesa), **não** xG de finalização.
- ✅ **Comparativo "quem chega melhor"** → **é só apresentação** no front (barra dupla lê `performancePct` e as médias de gol derivadas de `goalsFor/played`). Não precisa de campo novo; nenhum número é "inventado" no front, só proporção de barra.

Nada ficou no backlog.

---

## 8. Checklist pro backend

- [ ] Criar `GET /api/tournaments/{tid}/matches/{mid}/analysis` → `MatchAnalysisResponse` (§3), mesmo controle de acesso das rotas do torneio (owner/member ACTIVE/PUBLIC não-DRAFT; senão 404).
- [ ] `home`/`away` = mandante/visitante **desta** partida; `TeamRef` idêntico ao do `MatchResponse` (com `primaryColor`/`countryCode`).
- [ ] `recentMatches`: últimos **10** `COMPLETED` do time no torneio (§4.1/§4.2), mais recente primeiro, placar decisivo (§4.3), `outcome` (§4.4), pênaltis/`advanced` quando houver.
- [ ] `stats`: aproveitamento 3/1/0 fixo, streak, clean sheets, `overTwoFive`, `bothTeamsScored`, `homeRecord`/`awayRecord` (§4.5/§4.6/§4.7).
- [ ] `standing` conforme a tabela do §4.8 (reaproveitar a lógica de `/standings`); `null` no caso `NONE`.
- [ ] `headToHead` orientado aos times desta partida, com `averageGoals` (§4.9); `totalMeetings=0` quando nunca se enfrentaram.
- [ ] `predictors` por time (§4.11) — escopo todos os jogos do time no torneio com pitaco.
- [ ] `restDays` por time (§4.12).
- [ ] `expectedGoals` da partida (§4.13) — projeção pela forma; `null` se algum time sem jogos.
- [ ] Todos os percentuais em escala **0–100**; campos "média" como número decimal (o front formata).
