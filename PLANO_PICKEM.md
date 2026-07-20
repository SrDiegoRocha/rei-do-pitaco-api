# Planejamento — Pick'em de Fase

Feature nova: **Pick'em por fase do torneio**. Antes de cada fase começar, o participante faz um "palpitão" sobre o desfecho da fase inteira (não de uma partida). Acertos rendem pontos extras, **configuráveis pelo admin**, que **somam ao ranking geral** do torneio.

Este documento é o plano de implementação no backend (Java 21 / Spring Boot 3 / JPA / Flyway / PostgreSQL), seguindo a arquitetura em camadas já existente. Estilo e convenções espelham `CLAUDE.md`, `FEATURE.md` e `DETAILS.md`.

> **Status: ✅ implementado e verificado** (checklist na §13). O contrato final dos endpoints/DTOs para o front está no **`API.md` §20** (Pick'em), §7 (settings) e §18 (ranking) — em caso de divergência entre este plano e o `API.md`, vale o `API.md`.

---

## 1. Visão geral

Cada `TournamentPhase` ganha um **Pick'em**: um palpite de alto nível preenchido **antes da fase começar**. O formato do Pick'em depende do `phaseType`:

- **`ROUND_ROBIN` / `GROUPS`** (Pick'em de **tabela**): o palpiteiro ordena os classificados de cada tabela/grupo — quais times ficam na **zona de classificação** e em **quais posições**, incluindo quem termina em **1º**.
- **`KNOCKOUT`** (Pick'em de **chaveamento / bracket**): o palpiteiro preenche **quem passa** em cada confronto, montando o caminho até a final, e daí saem **campeão**, **vice** e **3º lugar** (quando a fase tem disputa de 3º).

O palpite é editável até a fase **travar** (início da 1ª partida da fase). Depois de travar, ninguém edita mais. A pontuação é calculada conforme os resultados da fase saem, mantendo o ranking em dia — mesma filosofia do recálculo automático dos palpites de partida.

**Objetivo de produto:** dar ao usuário camadas extras de "conquista" (acertei o grupo, cravei o mata-mata, previ o campeão) e enriquecer a competição social — os palpites de Pick'em são **visíveis entre os participantes**.

### Decisões já fechadas (definidas com o dono do produto)

| Tema | Decisão |
|---|---|
| **Pontos do Pick'em** | **Somam ao ranking geral** do torneio (junto com os pontos de palpite de partida). Um único ranking. |
| **Trava** | Trava no **início da 1ª partida da fase** (`min(scheduledAt)`; se a fase não tem horários, trava quando o **1º resultado** é lançado). |
| **Tabela (RR/GROUPS)** | Palpiteiro escolhe **zona + posição exata + 1º lugar**. Três componentes de pontuação independentes. |
| **Mata-mata** | Palpite fica disponível **após a 1ª rodada ser gerada** (o chaveamento inicial precisa existir). O usuário preenche o caminho inteiro de uma vez. |
| **Crédito parcial no KO** | **Exclusivo (o maior)**: por confronto, ou paga "confronto exato (A+B)" **ou** "pelo menos 1 time", nunca os dois somados. |
| **Visibilidade** | **Sempre visível**: qualquer member `ACTIVE` vê os Pick'ems dos outros a qualquer momento. |
| **Config de pontos** | Novos campos em **`TournamentSettings`** (torneio inteiro). Defaults `+1`. |

---

## 2. Modelo de dados

Três entidades novas. Uma "capa" (`PhasePrediction`) e duas de detalhe (uma para tabela, outra para bracket), mantendo separação de responsabilidades.

### 2.1 `PhasePrediction` (tabela `phase_predictions`)

A "capa" do Pick'em de um usuário numa fase.

- `id` (Long PK interna), `publicId` (UUID)
- `tournament` (FK imutável, **indexado** — o ranking agrega por torneio, igual a `Prediction`)
- `phase` (FK imutável)
- `user` (FK imutável)
- `phaseType` (enum `TournamentPhaseType`, snapshot do tipo no momento da criação — evita reinterpretar se a fase mudasse; a fase não muda de tipo em IN_PROGRESS, mas o snapshot deixa a leitura barata)
- `points` (int, **materializado**, default 0) — total deste Pick'em, recomputado quando resultados da fase mudam. É esta coluna que o ranking soma.
- `scoredAt` (Instant, nullable) — última vez que a pontuação foi (re)calculada; `null` = ainda não pontuado.
- `createdAt` / `updatedAt`
- **Unique** `(user_id, phase_id)` — um Pick'em por usuário/fase (semântica de upsert, igual a `Prediction`).
- Filhos: `@OneToMany` para `PhasePredictionPosition` e `PhasePredictionTie` (`orphanRemoval = true`, `cascade = ALL`).

### 2.2 `PhasePredictionPosition` (tabela `phase_prediction_positions`) — Pick'em de tabela

Uma linha por slot que o usuário preencheu no ranking de um grupo/tabela.

- `id` (Long PK)
- `phasePrediction` (FK imutável)
- `group` (FK `PhaseGroup`, **nullable** — `null` em `ROUND_ROBIN`, preenchido em `GROUPS`)
- `team` (FK `Team`) — o time que o usuário colocou naquele slot
- `predictedPosition` (int, `>= 1`) — a posição (dentro do grupo/tabela) em que o usuário cravou o time
- `createdAt`
- **Unique** `(phase_prediction_id, group_id, predicted_position)` e `(phase_prediction_id, group_id, team_id)` — não pode repetir posição nem time dentro do mesmo grupo. (Em Postgres, com `group_id` nullable, o unique trata `NULL` como distinto; para RR — sempre `group_id IS NULL` — usar índice único parcial equivalente, ver §3.)

O **conjunto de classificados previstos** = os times que aparecem nessas linhas dentro da faixa de classificação. O **1º previsto** = o time em `predictedPosition = 1` (por grupo). A **posição exata** = `predictedPosition` bate com a posição final real.

### 2.3 `PhasePredictionTie` (tabela `phase_prediction_ties`) — Pick'em de bracket

Uma linha por **slot de confronto** da árvore do mata-mata, preenchida com quem o usuário espera ali e quem avança.

- `id` (Long PK)
- `phasePrediction` (FK imutável)
- `roundNumber` (int, `>= 1`) — rodada do bracket (1 = primeira rodada, crescente até a final)
- `slotIndex` (int, `>= 0`) — posição do confronto **dentro da rodada**, na ordem canônica do bracket (ver §6.1)
- `matchType` (enum `MatchType`: `REGULAR` / `THIRD_PLACE`) — o slot de 3º lugar é modelado como um confronto próprio
- `predictedHomeTeam` (FK `Team`, nullable) — um dos dois times que o usuário espera nesse confronto
- `predictedAwayTeam` (FK `Team`, nullable) — o outro time
- `predictedWinnerTeam` (FK `Team`) — quem o usuário acha que avança (tem que ser um dos dois acima)
- `createdAt`
- **Unique** `(phase_prediction_id, round_number, slot_index, match_type)`.

Guardar o **par previsto** (`home`/`away`) além do vencedor é redundante (dá pra derivar da árvore), mas deixa a pontuação trivial e robusta: cada confronto real é comparado 1:1 com o slot previsto de mesmo `(roundNumber, slotIndex, matchType)`. O front envia a árvore inteira já resolvida.

- **Campeão** = `predictedWinnerTeam` do slot da **final** (`roundNumber = maxRound`, `matchType = REGULAR`, `slotIndex = 0`).
- **Vice** = o outro time (`predictedHomeTeam`/`predictedAwayTeam`) desse mesmo slot da final.
- **3º lugar** = `predictedWinnerTeam` do slot `matchType = THIRD_PLACE` (só existe quando `phase.hasThirdPlace`).

---

## 3. Migrations (Flyway)

Próximas versões livres: **V25** e **V26** (a última hoje é `V24`).

### V25 — pontuação de Pick'em em `tournament_settings`

Aditiva, `NOT NULL DEFAULT` (metadata-only no Postgres ≥ 11; torneios existentes recebem os defaults). Todos default `1`.

```sql
ALTER TABLE tournament_settings
    ADD COLUMN pickem_qualifier_points            INTEGER NOT NULL DEFAULT 1,  -- time que acaba classificado (na zona)
    ADD COLUMN pickem_exact_position_points       INTEGER NOT NULL DEFAULT 1,  -- time na posição exata
    ADD COLUMN pickem_first_place_points          INTEGER NOT NULL DEFAULT 1,  -- 1º do grupo/tabela
    ADD COLUMN pickem_ko_matchup_exact_points     INTEGER NOT NULL DEFAULT 1,  -- confronto A+B cravado
    ADD COLUMN pickem_ko_matchup_partial_points   INTEGER NOT NULL DEFAULT 1,  -- pelo menos 1 time no confronto
    ADD COLUMN pickem_champion_points             INTEGER NOT NULL DEFAULT 1,  -- campeão
    ADD COLUMN pickem_runner_up_points            INTEGER NOT NULL DEFAULT 1,  -- vice
    ADD COLUMN pickem_third_place_points          INTEGER NOT NULL DEFAULT 1;  -- 3º lugar
```

> Sugestão de tuning (não obrigatória): deixar `exact_position` e `ko_matchup_exact` valendo mais que os parciais, e `champion` > `runner_up` > `third_place`. Como tudo é configurável, começamos com `1` em tudo (o pedido) e o admin ajusta.

### V26 — tabelas do Pick'em

```sql
CREATE TABLE phase_predictions (
    id            BIGSERIAL PRIMARY KEY,
    public_id     UUID NOT NULL UNIQUE,
    tournament_id BIGINT NOT NULL REFERENCES tournaments (id),
    phase_id      BIGINT NOT NULL REFERENCES tournament_phases (id) ON DELETE CASCADE,
    user_id       BIGINT NOT NULL REFERENCES users (id),
    phase_type    VARCHAR(15) NOT NULL,
    points        INTEGER NOT NULL DEFAULT 0,
    scored_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_phase_prediction_user UNIQUE (user_id, phase_id)
);
CREATE INDEX idx_phase_predictions_tournament ON phase_predictions (tournament_id);
CREATE INDEX idx_phase_predictions_phase ON phase_predictions (phase_id);

CREATE TABLE phase_prediction_positions (
    id                   BIGSERIAL PRIMARY KEY,
    phase_prediction_id  BIGINT NOT NULL REFERENCES phase_predictions (id) ON DELETE CASCADE,
    group_id             BIGINT REFERENCES phase_groups (id),
    team_id              BIGINT NOT NULL REFERENCES teams (id),
    predicted_position   INTEGER NOT NULL CHECK (predicted_position >= 1),
    created_at           TIMESTAMPTZ NOT NULL
);
-- Unicidade de posição e de time por (pickem, grupo). Índices parciais cobrem group_id NULL (RR).
CREATE UNIQUE INDEX uq_ppp_pos_group ON phase_prediction_positions (phase_prediction_id, group_id, predicted_position) WHERE group_id IS NOT NULL;
CREATE UNIQUE INDEX uq_ppp_pos_nogroup ON phase_prediction_positions (phase_prediction_id, predicted_position) WHERE group_id IS NULL;
CREATE UNIQUE INDEX uq_ppp_team_group ON phase_prediction_positions (phase_prediction_id, group_id, team_id) WHERE group_id IS NOT NULL;
CREATE UNIQUE INDEX uq_ppp_team_nogroup ON phase_prediction_positions (phase_prediction_id, team_id) WHERE group_id IS NULL;

CREATE TABLE phase_prediction_ties (
    id                    BIGSERIAL PRIMARY KEY,
    phase_prediction_id   BIGINT NOT NULL REFERENCES phase_predictions (id) ON DELETE CASCADE,
    round_number          INTEGER NOT NULL CHECK (round_number >= 1),
    slot_index            INTEGER NOT NULL CHECK (slot_index >= 0),
    match_type            VARCHAR(15) NOT NULL DEFAULT 'REGULAR',
    predicted_home_team_id   BIGINT REFERENCES teams (id),
    predicted_away_team_id   BIGINT REFERENCES teams (id),
    predicted_winner_team_id BIGINT NOT NULL REFERENCES teams (id),
    created_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_ppt_slot UNIQUE (phase_prediction_id, round_number, slot_index, match_type)
);
```

---

## 4. Ciclo de vida (abrir → travar → pontuar)

Lógica central num `PhasePredictionService` (escrita) + `PhasePredictionScoringService` (pontuação). Não duplicar essas regras nos controllers.

### 4.1 Quando o Pick'em está **aberto** (aceita palpite)

Pré-condições (todas via um único guard `ensurePhasePredictionsOpen(phase)`):

1. Torneio em **`IN_PROGRESS`** (fora disso, 409 — mesma semântica dos palpites de partida).
2. Requester é **member `ACTIVE`** (403 `You are not an active member of this tournament`).
3. A fase tem o **substrato necessário**:
   - **Tabela (RR/GROUPS)**: `PhaseTeam` populados (times conhecidos) — em `GROUPS`, todos atribuídos a grupo.
   - **KNOCKOUT**: a **1ª rodada de matches já foi gerada** (senão 409 `Knockout bracket has not been generated yet`).
4. A fase **ainda não travou** (ver §4.2).

Uma fase futura (ex.: fase 2 de um torneio) só fica "aberta" depois que seus times/matches existem — ou seja, depois do `finalize` da fase anterior (tabela) ou da geração da 1ª rodada (KO). Antes disso o `GET .../template` responde `state = NOT_READY`.

### 4.2 Quando o Pick'em **trava**

Trava = **a 1ª partida da fase começou** (decisão do produto). Implementado em `ensurePhasePredictionsOpen`, espelhando `PredictionService.ensurePredictionsOpen`:

- Se **existe** partida com `scheduledAt`: trava quando `now >= min(scheduledAt)` da fase → 409 `Phase pick'em is locked (the phase has started)`.
- Se **nenhuma** partida tem `scheduledAt`: trava quando a **primeira** partida da fase vira `COMPLETED` (1º resultado lançado) → 409.
- Depois de travado: `PUT`/`DELETE` do meu Pick'em retornam 409.

> Consistência: a mesma janela que fecha o palpite de partida da 1ª partida fecha o Pick'em da fase.

### 4.3 Quando o Pick'em é **pontuado**

Pontuação **recalculada automaticamente** (mesma filosofia do `Prediction.points`):

- Em **`MatchService.setResult`** e **`MatchService.cancel`**: além do `predictionService.recalculatePointsFor(match)` que já existe, chamar `phasePredictionScoringService.recalculateForPhase(match.getPhase())`. Assim o Pick'em daquela fase é repontuado a cada resultado.
- Em **`PhaseFinalizeService.finalize`**: repontuar a fase ao final (garante o número definitivo quando as posições estabilizam).
- Endpoint owner de recálculo manual: `POST .../pickem/recalculate` (rede de segurança, igual ao recálculo de palpites).

A pontuação **antes da fase acabar é provisória** (projeção): usa a classificação/bracket *atual*. Isso é coerente com o `qualifies` provisório que o `StandingsService` já expõe. Ao terminar a fase, vira definitiva.

---

## 5. Pontuação — Pick'em de tabela (`ROUND_ROBIN` / `GROUPS`)

Fonte de verdade da classificação real: **`StandingsService.computeFor(tournament, phaseId)`** (a mesma do `finalize` e do endpoint `/standings`) — não reimplementar ordenação/tiebreak. Para cada `StandingRow` real temos `teamId`, `position`, `qualifies`, e o `groupId`.

Para cada `PhasePrediction` de tabela, percorrer as `PhasePredictionPosition` e somar (componentes **independentes/aditivos**):

1. **Classificado (zona)** — para cada time previsto cuja linha final tem `qualifies = true` → `+ pickemQualifierPoints`.
   - "Previsto como classificado" = o time aparece numa `predictedPosition` dentro da **faixa de classificação** da tabela/grupo (ver §5.1). Basta o time acabar classificado (não precisa ser na posição prevista).
2. **Posição exata** — para cada time cujo `predictedPosition` == posição final real (dentro do mesmo grupo) → `+ pickemExactPositionPoints`.
3. **1º lugar** — o time que o usuário pôs em `predictedPosition = 1` (por grupo/tabela) terminou em 1º → `+ pickemFirstPlacePoints`.

> Um time cravado em 1º e que de fato terminou em 1º e classificado pode somar os três componentes (classificado + posição exata + 1º). É o comportamento "várias conquistas somam" desejado. O admin zera qualquer componente que não quiser (default todos `1`).

### 5.1 Faixa de classificação e profundidade do palpite

A "faixa de classificação" vem das **`TournamentZone`** da fase (as que têm `nextPhase != null`). Define-se:

- `qualifyingDepth` = maior `toPosition` entre as zonas com `nextPhase != null` (cap no nº de times do grupo). É quantos slots o front renderiza por grupo/tabela para o usuário ordenar.
- Um time previsto conta como "previsto classificado" se seu `predictedPosition <= qualifyingDepth`.

**Caso BEST_RANKED (ex.: melhores 3ºs):** a zona de melhores-Nº-colocados é cross-grupo. Para o componente **classificado**, o que importa é o `qualifies` real (o `StandingsService`/`finalize` já resolve os melhores-N com os critérios do torneio) — então um 3º previsto que acabe entre os melhores-N conta como classificado. Simples e consistente. (Decisão em aberto D2 sobre refinar a UX disso.)

---

## 6. Pontuação — Pick'em de bracket (`KNOCKOUT`)

Fonte de verdade do bracket real: **`BracketService`** + **`TieAggregateCalculator`** (mesma base do chaveamento e da geração de rodadas). Reaproveitar o agrupamento por `tieId`, agregado e vencedor.

### 6.1 Indexação canônica dos slots

O bracket é uma **árvore binária**. Precisamos de um `(roundNumber, slotIndex)` estável para casar previsão × realidade:

- **Rodada** = `roundNumber` do bracket (o `BracketService` já ordena as rodadas da 1ª à final; em `TWO_LEGGED` as duas pernas do mesmo confronto contam como uma rodada via `tieId`).
- **`slotIndex`** dentro da rodada = ordem canônica dos confrontos `REGULAR` (a mesma que o `BracketService` usa: por `firstCreatedAt`, com 3º lugar à parte).
- **Encadeamento**: o vencedor do slot `2j` enfrenta o vencedor do slot `2j+1` no slot `j` da rodada seguinte. Isso vale porque a geração automática de KO **emparelha os vencedores em ordem** (ver `# Geração automática de partidas` → KNOCKOUT). O front reconstrói a árvore a partir da 1ª rodada usando essa regra.
- **3º lugar**: slot próprio com `matchType = THIRD_PLACE` na rodada da final.

> ✅ **Verificado e corrigido na implementação.** O `MatchGenerationService` iterava um `HashMap` para coletar os vencedores — a ordem de emparelhamento da rodada seguinte era efetivamente aleatória (não seguia a árvore do bracket). Corrigido: os confrontos agora são iterados em **ordem canônica** (mesma do bracket read model — criação da 1ª perna), então o vencedor do slot `2j` enfrenta o do `2j+1` como esperado. Além disso, a **pontuação não depende da posição do slot**: o matching é por rodada, casando pares exatos primeiro e maximizando os parciais (matching bipartido máximo) — robusto a brackets antigos/manuais que não seguem a árvore e nunca prejudica o palpiteiro.

### 6.2 Regras de pontuação

Para cada **confronto real resolvido** (cada `tieId` com vencedor definido), localizar o slot previsto de mesmo `(roundNumber, slotIndex, matchType)` e comparar o **par de times** (não-ordenado):

- **Confronto exato** (previu os **dois** times que realmente se enfrentaram) → `+ pickemKoMatchupExactPoints`.
- **Parcial** (previu **pelo menos 1** dos dois) → `+ pickemKoMatchupPartialPoints`.
- **Exclusivo (o maior)**: por confronto, aplica-se **apenas** o componente exato **ou** o parcial, nunca a soma (decisão do produto). Na prática: `if (ambos) exact; else if (um) partial; else 0`.

Terminal (uma vez decididos os slots correspondentes):

- **Campeão** — `predictedWinnerTeam` da final == campeão real → `+ pickemChampionPoints`.
- **Vice** — o finalista perdedor previsto (o outro time do slot da final previsto) == vice real → `+ pickemRunnerUpPoints`.
- **3º lugar** — só se `phase.hasThirdPlace`: `predictedWinnerTeam` do slot `THIRD_PLACE` == 3º real → `+ pickemThirdPlacePoints`.

> Os componentes de confronto e os terminais **somam** entre si (ex.: cravar a final como confronto exato + acertar o campeão + o vice). O "exclusivo" é só entre *exato vs parcial dentro do mesmo confronto*.

### 6.3 Pontuação progressiva

Como `recalculateForPhase` roda a cada `setResult`, o KO pontua rodada a rodada: acertou os confrontos das oitavas → pontos já entram; a final define campeão/vice/3º no fim. Confrontos ainda não resolvidos não pontuam.

---

## 7. Integração com o ranking geral

`RankingService.compute` hoje agrega `Prediction.points` (por partida), com filtros (fase, grupo, rodada, matchType, memberStatus). O Pick'em é **por fase**, não por partida — então a soma precisa respeitar o recorte:

- **Sem filtro** (ranking do torneio): somar `SUM(phase_predictions.points)` por usuário ao `totalPoints`.
- **Filtro só por `phaseId`**: somar apenas o `PhasePrediction` daquela fase.
- **Filtros mais estreitos** (`groupId`, `round`, `matchType`): **não** somar Pick'em (ele não é recortável por rodada/partida) — o ranking desses recortes continua só de palpites de partida.

Implementação: um `PhasePredictionRepository.sumPointsByUser(tournamentPublicId, phaseId?)` retornando `Map<userId, points>` para somar no `Accumulator`. Usuários que só têm Pick'em (sem palpite de partida) também precisam entrar no ranking — então o accumulator deve ser semeado com quem tem Pick'em, não só com quem tem `Prediction`.

Campos do `RankingRowResponse`: **sem breakdown** — `totalPoints` é o grande total (palpites + Pick'em) e ponto. **Decidido (D1):** o detalhamento (quanto veio de Pick'em vs palpites de partida, por fase, explicado) mora no **perfil do palpiteiro**, não no ranking. Ver §7.1.

### 7.1 Perfil do palpiteiro no torneio (detalhamento)

Endpoint novo, tournament-level (não fica sob `/phases/{pid}`):

```
GET /api/tournaments/{tid}/participants/{userId}/summary  → 200 ParticipantSummaryResponse
```

Acesso via `TournamentAccessGuard`. Retorna o desempenho **explicado e separado** de um participante:

```jsonc
{
  "userId": "uuid", "userName": "Fulano", "avatarUrl": "...",
  "rankingPosition": 3,
  "totalPoints": 42,           // == ranking
  "matchPoints": 30,           // soma de Prediction.points
  "pickemPoints": 12,          // soma de PhasePrediction.points
  "matchBreakdown": {          // reaproveita os contadores do ranking
    "totalPredictions": 40, "exactScoreHits": 8, "winnerHits": 15, "wrongs": 12
  },
  "pickemByPhase": [           // um item por fase que o usuário palpitou
    { "phaseId": "uuid", "phaseName": "Fase de Grupos", "phaseType": "GROUPS",
      "points": 7,
      "components": { "qualifier": 4, "exactPosition": 2, "firstPlace": 1,
                     "koMatchup": 0, "koPartial": 0, "champion": 0, "runnerUp": 0, "thirdPlace": 0 } },
    { "phaseId": "uuid", "phaseName": "Mata-mata", "phaseType": "KNOCKOUT",
      "points": 5,
      "components": { "qualifier": 0, "exactPosition": 0, "firstPlace": 0,
                     "koMatchup": 2, "koPartial": 1, "champion": 1, "runnerUp": 1, "thirdPlace": 0 } }
  ]
}
```

Para o breakdown por componente sair barato, o `PhasePredictionScoringService` deve gravar não só o `points` total do Pick'em mas também a decomposição. Duas opções (decisão de implementação, não de produto): (a) recomputar on-demand no `summary` a partir dos picks + resultado real, ou (b) materializar os componentes em colunas no `PhasePrediction`. Recomendo **(a)** — evita colunas extras e o cálculo é barato.

---

## 8. Endpoints

Base: `/api/tournaments/{tid}/phases/{pid}/pickem`. Todos exigem `Authorization: Bearer <access-token>`. Acesso de leitura via **`TournamentAccessGuard.requireViewable`** (owner, member `ACTIVE`, ou `PUBLIC` não-DRAFT); escrita exige member `ACTIVE`.

| Método | Path | Status | Descrição |
| ------ | ---- | ------ | --------- |
| GET | `/template` | 200 | Estrutura a preencher + config de pontos + estado (aberto/travado/não-pronto). O front renderiza a UI a partir daqui. |
| PUT | `/me` | 200 | Upsert do meu Pick'em (cria ou substitui). Bloqueado se travado. |
| GET | `/me` | 200 | Meu Pick'em (com meus pontos, nunca redigido). |
| DELETE | `/me` | 204 | Remove meu Pick'em (até travar). |
| GET | `/` | 200 | Lista paginada dos Pick'ems de todos os participantes (**sempre visível**). |
| GET | `/{userId}` | 200 | Pick'em de um participante específico. |
| GET | `/stats` | 200 | Agregados (ex.: % que cravou cada campeão / 1º de cada grupo) — sem depender de trava. |
| POST | `/recalculate` | 200 | **Owner-only**. Força o recálculo da pontuação do Pick'em da fase. |

`{pid}`, `{userId}` são `publicId` (UUID).

> Além destes, há **um endpoint tournament-level** (fora da base `/phases/{pid}`): `GET /api/tournaments/{tid}/participants/{userId}/summary` — o perfil/detalhamento do palpiteiro (§7.1).

### 8.1 `GET /template` — resposta (esboço)

```jsonc
{
  "phaseId": "uuid",
  "phaseType": "GROUPS",              // ROUND_ROBIN | GROUPS | KNOCKOUT
  "state": "OPEN",                    // NOT_READY | OPEN | LOCKED
  "lockAt": "2026-07-25T18:00:00Z",   // min(scheduledAt) da fase; null se trava por 1º resultado
  "scoring": {                        // ecoa TournamentSettings (pontos do Pick'em)
    "qualifierPoints": 1, "exactPositionPoints": 1, "firstPlacePoints": 1,
    "koMatchupExactPoints": 1, "koMatchupPartialPoints": 1,
    "championPoints": 1, "runnerUpPoints": 1, "thirdPlacePoints": 1
  },

  // Só em RR/GROUPS:
  "table": {
    "qualifyingDepth": 2,             // quantos slots ranquear por grupo
    "groups": [
      { "groupId": "uuid", "groupName": "Grupo A",
        "teams": [ { "id": "uuid", "name": "Brasil", "shortName": "BRA",
                     "teamType": "NATIONAL_TEAM", "countryCode": "br", "...": "TeamRef" } ] }
      // RR: uma entrada com groupId = null
    ]
  },

  // Só em KNOCKOUT:
  "bracket": {
    "hasThirdPlace": true,
    "rounds": [
      { "roundNumber": 1, "name": "Quarterfinals",
        "slots": [ { "slotIndex": 0, "homeTeam": { "...": "TeamRef" }, "awayTeam": { "...": "TeamRef" } } ] },
      { "roundNumber": 2, "name": "Semifinals", "slots": [ { "slotIndex": 0, "homeTeam": null, "awayTeam": null } ] }
      // rodadas futuras vêm com times null (o front preenche a partir dos vencedores escolhidos)
    ]
  }
}
```

### 8.2 `PUT /me` — request

**Tabela (RR/GROUPS):**
```jsonc
{
  "positions": [
    { "groupId": "uuid-ou-null", "teamId": "uuid", "predictedPosition": 1 },
    { "groupId": "uuid-ou-null", "teamId": "uuid", "predictedPosition": 2 }
  ]
}
```

**KNOCKOUT:**
```jsonc
{
  "ties": [
    { "roundNumber": 1, "slotIndex": 0, "matchType": "REGULAR",
      "homeTeamId": "uuid", "awayTeamId": "uuid", "winnerTeamId": "uuid" },
    { "roundNumber": 2, "slotIndex": 0, "matchType": "REGULAR",
      "homeTeamId": "uuid-vencedor-slot0", "awayTeamId": "uuid-vencedor-slot1", "winnerTeamId": "uuid" },
    { "roundNumber": 2, "slotIndex": 0, "matchType": "THIRD_PLACE",
      "homeTeamId": "uuid", "awayTeamId": "uuid", "winnerTeamId": "uuid" }
  ]
}
```

Validações do `PUT` (400/409 conforme o caso):
- **Tabela**: `predictedPosition` em `1..qualifyingDepth`; sem posição/time repetido no grupo; `teamId` pertence ao grupo/fase; `groupId` obrigatório em GROUPS e proibido em RR; preencher **todos** os slots de classificação (ou permitir parcial? — decisão D3).
- **KNOCKOUT**: `winnerTeamId ∈ {homeTeamId, awayTeamId}`; a 1ª rodada tem que casar com os confrontos reais (times e slots); rodadas seguintes têm que ser **coerentes com os vencedores escolhidos** na rodada anterior (o back valida a árvore, não confia cegamente); `THIRD_PLACE` só se `hasThirdPlace`.

### 8.3 `PredictionResponse` de Pick'em (leitura)

Como é **sempre visível**, não há redação — qualquer member ACTIVE vê o palpite dos outros com times e (quando já pontuado) os pontos:

```jsonc
{
  "id": "uuid", "phaseId": "uuid", "userId": "uuid", "userName": "Fulano",
  "phaseType": "KNOCKOUT",
  "points": 4, "scoredAt": "2026-07-26T22:00:00Z",
  "positions": [ /* ... quando tabela */ ],
  "ties": [ /* ... quando bracket, com breakdown de acerto por slot na leitura, ver D4 */ ],
  "createdAt": "...", "updatedAt": "..."
}
```

---

## 9. Estrutura de código (arquivos novos)

Seguindo o layout de `src/main/java/com/example/reidopitaco`:

- **entity/**: `PhasePrediction`, `PhasePredictionPosition`, `PhasePredictionTie`
- **repository/**: `PhasePredictionRepository`, `PhasePredictionPositionRepository`, `PhasePredictionTieRepository`
- **dto/request/**: `PlacePhasePredictionRequest` (com `positions[]` e `ties[]`), records aninhados `PositionPick`, `TiePick`
- **dto/response/**: `PhasePredictionTemplateResponse`, `PhasePredictionResponse`, `PhasePredictionStatsResponse`
- **service/**: `PhasePredictionService` (abrir/travar/upsert/listar), `PhasePredictionScoringService` (pontuação tabela + bracket), `PhasePredictionTemplateService` (monta o template — pode ficar dentro do Service principal)
- **mapper/**: `PhasePredictionMapper`
- **controller/**: `PhasePredictionController`
- **exception/**: reaproveitar os handlers globais; novas exceptions só se precisar de mensagem/status específicos (ex.: `PhasePredictionLockedException` → 409)
- **enums/**: reusar `TournamentPhaseType`, `MatchType`; nenhum enum novo previsto
- **db/migration/**: `V25__...sql`, `V26__...sql`

Alterações em código existente:
- `TournamentSettings` (+8 campos) e `TournamentSettingsPayload`/`TournamentSettingsResponse` (semântica: no create assume defaults, no update ausente **preserva** — igual aos campos de prorrogação da V24).
- `MatchService.setResult` / `MatchService.cancel` → chamar `phasePredictionScoringService.recalculateForPhase(phase)`.
- `PhaseFinalizeService.finalize` → repontuar a fase ao final.
- `RankingService.compute` → somar pontos de Pick'em conforme o recorte (§7).
- `TournamentMapper`/settings mapper → expor os novos campos.

---

## 10. Fases de implementação sugeridas

1. **Config + infra**: V25 (settings) + expor os 8 campos nos DTOs de settings. Sem comportamento ainda. (baixo risco, aditivo)
2. **Modelo + escrita**: V26, entidades, repositórios, `PhasePredictionService`, `GET /template`, `PUT/GET/DELETE /me`, lock. Sem pontuação.
3. **Listagem/visibilidade**: `GET /`, `GET /{userId}`, `GET /stats`.
4. **Pontuação de tabela**: `PhasePredictionScoringService` (RR/GROUPS) + hooks em `setResult`/`cancel`/`finalize` + soma no ranking.
5. **Pontuação de bracket**: mapeamento de slots (§6.1) + regras KO + terminais + testes 4/8/16 times.
6. **Recalculate** owner + polimento (breakdown de pontos, stats).

Cada fase é testável de ponta a ponta isoladamente.

---

## 11. Casos de borda

- **Fase não-pronta**: `template` responde `NOT_READY` (times/bracket ainda não existem). `PUT /me` → 409.
- **Empate no agregado do KO sem pênaltis**: confronto sem vencedor real → esse slot não pontua e não define os subsequentes até resolver (igual ao bloqueio da geração de rodada).
- **Time desvinculado / soft-deletado** entre o palpite e o resultado: o Pick'em guarda FK direto pro `Team` (por id), então continua resolvendo; a pontuação usa os times reais das partidas.
- **Owner edita resultado depois de finalizar**: `finalize` não roda 2x, mas `setResult` (permitido em IN_PROGRESS) já dispara `recalculateForPhase`. Se precisar reabrir, usar `POST /recalculate`.
- **Usuário entrou no torneio depois da fase travar**: não consegue palpitar aquela fase (travada) — normal. Pode palpitar fases futuras ainda abertas.
- **Zonas alteradas depois do palpite** (zonas são editáveis até FINISHED): `qualifyingDepth` e o `qualifies` real são recalculados na pontuação; o palpite guarda posições, então segue válido. Documentar que mudar zonas depois de travado altera a base de pontuação.
- **Palpite parcial** (usuário não preencheu tudo): ver D3.
- **KO com só 2 times** (final direta): 1 rodada, campeão/vice; 3º lugar só se configurado (não faz sentido com 2 times) — validar.

---

## 12. Decisões

### Resolvidas

- **D1 — Breakdown ✅.** Ranking = só o total somado (sem breakdown). O detalhamento (pickem vs match, por fase, por componente) vive no **perfil do palpiteiro** (`GET /participants/{userId}/summary`, §7.1).
- **D3 — Palpite parcial ✅ permitido.** O usuário pode salvar o Pick'em incompleto; slots não preenchidos simplesmente não pontuam. O front pode alertar, mas o backend aceita.
- **D4 — Breakdown por item na leitura ✅.** `GET /me` e `GET /{userId}` retornam, em cada item (posição/confronto), um flag de acerto (`outcome: EXACT | PARTIAL | MISS | HIT`) e `pointsAwarded`, calculados on-demand contra o resultado real. Antes de haver resultado, `outcome = null`.
- **D6 — Pontuação provisória ✅.** Os pontos aparecem provisórios durante a fase (recalculados a cada resultado) e viram definitivos no fim. `scoredAt` sinaliza a última atualização.
- **D7 — Sem zonas ✅.** Fase RR/GROUPS **sem `TournamentZone`** não abre Pick'em de classificação: `GET /template` responde `state = NOT_READY` (com um motivo, ex.: `reason: "NO_QUALIFICATION_ZONES"`). Sem faixa de classificação definida, não há o que prever.
- **D2 — Melhores 3ºs (BEST_RANKED) ✅ abordagem simples.** O componente "classificado" olha só o `qualifies` real — o usuário posiciona os times (incl. 3ºs) e o backend confere quem passou. Sem UI/pontuação dedicada pros melhores 3ºs.
- **D5 — Conteúdo do `/stats` ✅ padrão.** Distribuição de **campeão/vice/3º** (KO) e, por grupo, o time mais escolhido pra **1º** e pra **classificar** (tabela). Só contagens/percentuais, nunca palpites individuais.

Todas as decisões estão fechadas — nada pendente.

---

## 13. Checklist de implementação (backend)

> **Status: implementado.** Compilação limpa, migrations V25/V26 aplicadas com sucesso (Postgres 16), e dois E2E de fumaça contra a API real (47 asserts, todos verdes). Detalhes de verificação no fim da lista.

- [x] V25: 8 colunas de pontuação de Pick'em em `tournament_settings` (defaults 1) + DTOs de settings (create=default, update=preserva).
- [x] V26: `phase_predictions`, `phase_prediction_positions`, `phase_prediction_ties` + índices únicos parciais.
- [x] Entidades + repositórios + mappers.
- [x] `PhasePredictionService`: guard de abrir/travar, upsert (`PUT /me`), `GET /me`, `DELETE /me`, `GET /`, `GET /{userId}`. (Guard/contexto compartilhado em `PhasePredictionContextService` — fonte única entre template e validação.)
- [x] `GET /template`: monta estrutura por tipo de fase + estado (`NOT_READY`/`OPEN`/`LOCKED` + `stateReason`) + scoring.
- [x] `PhasePredictionScoringService`: pontuação de tabela (§5) e de bracket (§6), reusando `StandingsService`/`TieAggregateCalculator`. Matching de confrontos por rodada (exatos primeiro, parciais via matching bipartido máximo) — robusto a brackets manuais/antigos.
- [x] Hooks: `MatchService.setResult`/`cancel` e `PhaseFinalizeService.finalize` → `recalculateForPhase`.
- [x] `RankingService`: soma Pick'em respeitando o recorte de filtros (§7) e semeia usuários que só têm Pick'em. (Sem breakdown no ranking.)
- [x] `GET /participants/{userId}/summary`: perfil do palpiteiro com breakdown pickem/match e por fase/componente (§7.1).
- [x] Breakdown por item (`outcome`/`pointsAwarded`/`terminals`) nas respostas de leitura (D4), calculado on-demand.
- [x] `POST /recalculate` owner-only (retorna `pickemsRecalculated`).
- [x] `GET /stats` agregado (campeão/vice/3º no KO; 1º e classificados por grupo na tabela; maior-resto nas distribuições de escolha única).
- [x] **Bônus** — corrigido bug pré-existente no `MatchGenerationService`: o emparelhamento da próxima rodada do KO iterava um `HashMap` (ordem aleatória); agora segue a ordem canônica do bracket (`2j` × `2j+1`). Ver §6.1.
- [x] Verificação executada: `mvnw test` (context load + Flyway V25/V26 no Postgres real) e 2 scripts E2E dirigindo a API — fluxo RR→KO completo (template, upsert, validações 400, trava 409, pontuação provisória→definitiva, ranking com usuário só-Pick'em, summary, recalculate/403) e KO 4 times com 3º lugar (EXACT/PARTIAL, terminais, `winnerAdvanced`, emparelhamento canônico).
- [x] **Pedido do front (`PICKEM_FRONT_API.md`)**: `GET /api/users/me/pickems/pending` — pendências pro card "Palpitão aberto" da home (mesmo guard do template, ordenado por urgência). Documentado no `API.md` §20.9 e verificado com E2E.
- [ ] Suite de testes de integração **permanente** no repo (o projeto hoje só tem o context-load test): cobrir GROUPS (incl. `BEST_RANKED`), KO 8/16 times e `TWO_LEGGED` — cenários ainda não exercitados pelos E2E de fumaça.
- [x] Contrato para o front: documentado direto no `API.md` §20 (9 subseções com tipagens TS), §7 (settings) e §18 (ranking) — enviar `API.md` + este plano.
```
