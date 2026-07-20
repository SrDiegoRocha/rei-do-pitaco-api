# Feature: Prorrogação e pênaltis na pontuação do mata-mata

Documento para o front. Descreve **tudo que mudou** no backend: regras, DTOs (request/response), endpoints e mensagens de erro.

Nenhuma URL de endpoint mudou — o que mudou foram os **campos** dos payloads e as **regras de validação/pontuação**. Todos os campos JSON são `camelCase`.

---

## 1. Visão geral

Mata-mata de **jogo único** (`KNOCKOUT` + `matchLegMode = SINGLE`) agora tem três momentos de placar e três blocos de pontuação, que **se somam**:

1. **Tempo normal** (90 min) — sempre existiu.
2. **Prorrogação** (extra time) — **novo**. Só acontece quando o tempo normal empata.
3. **Pênaltis** — já existia o palpite de "quem passa"; agora virou pontuação própria e configurável.

A pontuação de cada bloco é **configurável pelo admin** do torneio. Exemplo com os defaults `2 / 2 / 2`:

> Palpiteiro cravou 2×2 no tempo normal (+2), acertou o placar exato 3×3 da prorrogação (+2) e acertou quem passou nos pênaltis (+2) = **6 pontos**.

Ida-e-volta (`TWO_LEGGED`), grupos e pontos corridos **não têm prorrogação** — nada muda pra eles além do que já existia.

---

## 2. Modelo de pontuação (como o total é calculado)

O `points` de um palpite é a **soma** dos componentes aplicáveis:

### Bloco 1 — Tempo normal (sempre conta)
Comparando o palpite do 90' (`homeScore`/`awayScore`) com o placar real do 90':
| Situação | Pontos |
|---|---|
| Acertou o placar exato | `exactScorePoints` (default 5) |
| Errou o placar, acertou o vencedor/empate | `winnerPoints` (default 2) |
| Errou o desfecho | `wrongPoints` (default 0) |

### Bloco 2 — Prorrogação (só quando a partida foi à prorrogação, KO jogo único)
Comparando o palpite da prorrogação (`homeExtraTimeScore`/`awayExtraTimeScore`) com o placar real da prorrogação. **Só conta se o palpiteiro informou o placar da prorrogação.**
| Situação | Pontos |
|---|---|
| Acertou o placar exato da prorrogação | `extraTimeExactScorePoints` (default 2) |
| Errou o placar, acertou quem vence a prorrogação | `extraTimeWinnerPoints` (default 1) |
| Errou o desfecho da prorrogação | 0 (não há "erro" configurável aqui) |

### Bloco 3 — Pênaltis (só quando o confronto foi decidido nos pênaltis)
| Situação | Pontos |
|---|---|
| Acertou quem passa (`penaltyWinner`) | `penaltyWinnerPoints` (default 2) |
| Errou | 0 |

> ⚠️ **Mudança de comportamento:** antes os pênaltis eram *misturados* no mesmo balde do tempo normal. Agora são um bloco **somado à parte**. O total pode ser maior que antes.

> Placar da prorrogação é **cumulativo** (inclui os gols do tempo normal). Ex.: 1×1 no normal → a prorrogação começa em 1×1 e só sobe.

---

## 3. Configuração do torneio (admin)

Três campos novos em `TournamentSettings`, todos `>= 0`.

### Request — `TournamentSettingsPayload` (dentro de `CreateTournamentRequest` / `UpdateTournamentRequest`)

Campos novos (**opcionais**):

```jsonc
{
  "winPoints": 3,
  "drawPoints": 1,
  "lossPoints": 0,
  "exactScorePoints": 5,
  "winnerPoints": 2,
  "wrongPoints": 0,

  // ↓↓↓ NOVOS (opcionais) ↓↓↓
  "extraTimeExactScorePoints": 2,   // placar exato da prorrogação
  "extraTimeWinnerPoints": 1,       // só o vencedor da prorrogação
  "penaltyWinnerPoints": 2,         // quem passa nos pênaltis

  "tiebreakCriteria": ["POINTS", "GOAL_DIFFERENCE", "GOALS_FOR"]
}
```

Comportamento dos três campos novos:
- **No CREATE:** ausentes (`null` ou não enviados) → assumem os defaults `2 / 1 / 2`.
- **No UPDATE:** ausentes → **preservam o valor atual** (não zeram). Envie o campo só quando quiser alterá-lo.
- Isso mantém o front atual funcionando sem mudar nada. Pra deixar o admin editar esses valores, adicione os três inputs na tela de configuração do torneio.

### Response — `TournamentSettingsResponse` (dentro de `TournamentResponse`)

Sempre traz os três novos campos preenchidos:

```jsonc
{
  "winPoints": 3, "drawPoints": 1, "lossPoints": 0,
  "exactScorePoints": 5, "winnerPoints": 2, "wrongPoints": 0,
  "extraTimeExactScorePoints": 2,
  "extraTimeWinnerPoints": 1,
  "penaltyWinnerPoints": 2,
  "tiebreakCriteria": ["POINTS", "GOAL_DIFFERENCE"]
}
```

---

## 4. Palpite do usuário (a parte mais importante pro front)

### Endpoint (inalterado)
```
PUT /api/tournaments/{tournamentId}/matches/{matchId}/predictions/me
```

### Request — `PlacePredictionRequest`

```jsonc
{
  "homeScore": 2,              // obrigatório, >= 0  (placar do tempo normal)
  "awayScore": 2,              // obrigatório, >= 0

  "homeExtraTimeScore": 3,     // opcional, >= 0  (placar da prorrogação, cumulativo)
  "awayExtraTimeScore": 3,     // opcional, >= 0

  "penaltyWinner": "HOME"      // opcional, "HOME" | "AWAY"  (quem passa nos pênaltis)
}
```

### Cascata obrigatória (UX do formulário) — só em KO jogo único

O front deve conduzir o preenchimento em cascata. A regra é validada no backend (400 se violada), mas o ideal é o front já guiar:

```
1. Usuário informa homeScore / awayScore (tempo normal).

2. É empate no tempo normal? (homeScore == awayScore)
   ├─ NÃO → não mostra prorrogação nem pênaltis. Enviar homeExtraTimeScore/awayExtraTimeScore/penaltyWinner = null.
   └─ SIM → mostra os campos de PRORROGAÇÃO (obrigatórios).
            Placar mínimo de cada time = o placar do time no tempo normal.
            (ex.: 1×1 no normal → prorrogação não pode ser menor que 1×1)

3. A prorrogação palpitada é empate? (homeExtraTimeScore == awayExtraTimeScore)
   ├─ NÃO → não mostra pênaltis. Enviar penaltyWinner = null.
   └─ SIM → mostra o seletor de PÊNALTIS (obrigatório): "quem passa?" → HOME ou AWAY.
```

**Exemplos de body válidos:**

Palpite com vencedor no tempo normal (sem prorrogação/pênaltis):
```json
{ "homeScore": 2, "awayScore": 1 }
```

Empate no tempo normal, vencedor na prorrogação (sem pênaltis):
```json
{ "homeScore": 1, "awayScore": 1, "homeExtraTimeScore": 2, "awayExtraTimeScore": 1 }
```

Empate no tempo normal e na prorrogação, decidido nos pênaltis:
```json
{ "homeScore": 1, "awayScore": 1, "homeExtraTimeScore": 2, "awayExtraTimeScore": 2, "penaltyWinner": "HOME" }
```

> `penaltyWinner` é o **lado do confronto** desta partida: `HOME` = mandante, `AWAY` = visitante (não é o publicId do time).

### Erros de validação do palpite (HTTP **400**, formato `ApiError`)

| `message` | Quando |
|---|---|
| `extra-time score is required when you predict a draw in a single-leg knockout` | Empatou no 90' mas não mandou a prorrogação |
| `extra-time score cannot be lower than your regular-time score` | Prorrogação menor que o placar do 90' |
| `penaltyWinner is required when your extra-time prediction is a draw` | Prorrogação empatada mas não escolheu quem passa |
| `penaltyWinner only applies when your extra-time prediction is a draw` | Mandou `penaltyWinner` mas a prorrogação palpitada tem vencedor |
| `extraTimeScore only applies when you predict a draw in regular time` | Mandou prorrogação sem empatar no 90' |
| `penaltyWinner only applies when your prediction ends level` | Mandou `penaltyWinner` sem empatar no 90' |
| `extraTimeScore only applies to a single-leg knockout match` | Mandou prorrogação em partida que não é KO jogo único |

Ida-e-volta (2ª perna, empate no agregado) mantém as regras antigas de `penaltyWinner`:
`penaltyWinner is required when your prediction ends the tie in a draw` / `penaltyWinner only applies when your prediction ends the tie in a draw`.

### Response — `PredictionResponse`

```jsonc
{
  "id": "uuid-do-palpite",
  "matchId": "uuid-da-partida",
  "userId": "uuid-do-usuario",
  "userName": "Fulano",
  "homeScore": 1,
  "awayScore": 1,
  "homeExtraTimeScore": 2,   // NOVO — null se não palpitou prorrogação
  "awayExtraTimeScore": 2,   // NOVO
  "penaltyWinner": "HOME",
  "points": 6,
  "createdAt": "2026-07-01T20:00:00Z",
  "updatedAt": "2026-07-01T20:05:00Z"
}
```

> **Redação (privacidade):** ao listar palpites alheios antes do jogo abrir, os campos `homeScore`, `awayScore`, `homeExtraTimeScore`, `awayExtraTimeScore`, `penaltyWinner` e `points` vêm **`null`**. São revelados na mesma janela de sempre (após `scheduledAt`, ou após o resultado quando não há horário). Nada mudou nessa regra — só entraram os dois campos de prorrogação na redação.

### Listagem de palpites — `GET /api/tournaments/{tid}/matches/{mid}/predictions`

Continua retornando `PredictionResponse[]` (com os novos campos de prorrogação acima). O placar **real** da partida (incluindo prorrogação/pênaltis) vem no `MatchResponse` — busque a partida à parte para comparar palpite × resultado.

### Estatísticas — `GET /api/tournaments/{tid}/matches/{mid}/predictions/stats`

Distribuição mandante/empate/visitante (`PredictionStatsResponse`). O desfecho de cada palpite agora considera a **prorrogação**: usa `homeExtraTimeScore`/`awayExtraTimeScore` quando o palpiteiro informou, senão o placar do tempo normal.
- Ex.: palpite 1×1 no normal e 2×1 na prorrogação conta como **vitória do mandante**, não empate.
- Pênaltis não entram na distribuição: prorrogação empatada (vai a pênaltis) conta como **empate**.

---

## 5. Lançar resultado (admin/owner)

### Endpoint (inalterado)
```
PUT /api/tournaments/{tournamentId}/phases/{phaseId}/matches/{matchId}/result
```

### Request — `SetMatchResultRequest`

```jsonc
{
  "homeScore": 1,              // obrigatório, >= 0  (tempo normal)
  "awayScore": 1,              // obrigatório, >= 0

  "homeExtraTimeScore": 2,     // NOVO — opcional, >= 0 (cumulativo)
  "awayExtraTimeScore": 2,     // NOVO — opcional

  "homePenalties": 4,          // opcional, >= 0
  "awayPenalties": 3           // opcional
}
```

Fluxo esperado do resultado real em KO jogo único: 90' empatado → informa prorrogação → se prorrogação empatada → informa pênaltis.

### Erros ao lançar resultado (HTTP **409**, formato `ApiError`)

| `message` | Quando |
|---|---|
| `Both extra-time scores must be provided together` | Mandou só um dos dois placares da prorrogação |
| `Extra time only applies to single-leg KNOCKOUT matches` | Prorrogação fora de KO jogo único |
| `Extra time only applies when regular time ended in a draw` | Prorrogação com o 90' não empatado |
| `Extra-time score cannot be lower than the regular-time score` | Prorrogação menor que o 90' |
| `Penalties only apply when extra time ended in a draw` | Pênaltis com prorrogação lançada mas não empatada |
| `Both penalty scores must be provided together` | Mandou só um dos dois placares de pênalti |
| `Penalty shootout cannot end in a draw` | Pênaltis empatados |

> `PUT .../cancel` zera prorrogação junto com placares e pênaltis.
> Editar um resultado já lançado recalcula os pontos automaticamente.

---

## 6. Detalhe da partida — `MatchResponse`

Dois campos novos (entre `awayScore` e `homePenalties`):

```jsonc
{
  "id": "uuid",
  "phaseId": "uuid",
  "round": 1,
  "matchType": "REGULAR",
  "homeTeam": { "...": "TeamRef" },
  "awayTeam": { "...": "TeamRef" },
  "scheduledAt": "2026-07-10T18:00:00Z",
  "homeScore": 1,
  "awayScore": 1,
  "homeExtraTimeScore": 2,   // NOVO — null se não houve prorrogação
  "awayExtraTimeScore": 2,   // NOVO
  "homePenalties": 4,
  "awayPenalties": 3,
  "penaltyShootoutEligible": true,   // já existia: confronto pode ir aos pênaltis
  "aggregateBeforeHome": 0,
  "aggregateBeforeAway": 0,
  "status": "COMPLETED",
  "createdAt": "...",
  "updatedAt": "..."
}
```

> Bracket (`GET .../bracket`): quando houve prorrogação, o placar agregado do confronto (`homeAggregate`/`awayAggregate`) já reflete o **placar da prorrogação** (placar decisivo). O vencedor e a geração da próxima rodada seguem a prorrogação; os pênaltis só decidem se a prorrogação empatar.

---

## 7. Feed pessoal — `UserMatchResponse`

Endpoint `GET /api/users/me/matches` (inalterado). Dois blocos ganharam campos:

`tournament.scoring` (`ScoringRef`) — agora traz a pontuação completa pra pintar os chips:
```jsonc
{
  "exactScorePoints": 5,
  "winnerPoints": 2,
  "wrongPoints": 0,
  "extraTimeExactScorePoints": 2,   // NOVO
  "extraTimeWinnerPoints": 1,       // NOVO
  "penaltyWinnerPoints": 2          // NOVO
}
```

`myPrediction` (`MyPrediction`) — traz o palpite de prorrogação:
```jsonc
{
  "id": "uuid",
  "homeScore": 1,
  "awayScore": 1,
  "homeExtraTimeScore": 2,   // NOVO — null se não palpitou prorrogação
  "awayExtraTimeScore": 2,   // NOVO
  "penaltyWinner": "HOME",
  "points": 6
}
```

> Como o feed já traz `phase.phaseType` e `phase.matchLegMode`, o front consegue saber que é **KO jogo único** e decidir quando mostrar os campos de prorrogação/pênaltis.

---

## 8. Como saber quando mostrar prorrogação/pênaltis no formulário

Prorrogação/pênaltis só entram quando a fase é **`KNOCKOUT` + `matchLegMode = SINGLE`**:
- **No feed pessoal** (`UserMatchResponse`): use `phase.phaseType === "KNOCKOUT" && phase.matchLegMode === "SINGLE"`.
- **No `MatchResponse` cru** (detalhe/lista de partidas): ele **não** carrega `phaseType`/`matchLegMode`. Use o `penaltyShootoutEligible` como sinal de que o confronto pode ir a pênaltis, ou busque a fase pelo `phaseId`. Se precisar de um flag explícito `extraTimeEligible` no `MatchResponse`, é fácil de adicionar — só pedir.

Dentro disso, a cascata da seção 4 decide, a partir dos valores digitados, quais campos abrir.

---

## 9. Migrations (infra — só pra ciência)

- **V23** — colunas nullable `home_extra_time_score`/`away_extra_time_score` em `tournament_matches` e `predictions`.
- **V24** — colunas `extra_time_exact_score_points` (2), `extra_time_winner_points` (1), `penalty_winner_points` (2) em `tournament_settings`, com default.

Mudanças aditivas e não-destrutivas; torneios existentes recebem os defaults automaticamente. Pontos de partidas já encerradas só recalculam no próximo lançamento de resultado ou via o endpoint owner de recálculo.

---

## 10. Checklist rápido pro front

- [ ] Tela de config do torneio: 3 inputs novos (`extraTimeExactScorePoints`, `extraTimeWinnerPoints`, `penaltyWinnerPoints`) — opcionais, defaults 2/1/2.
- [ ] Formulário de palpite (KO jogo único): cascata 90' → prorrogação (mínimo = placar do 90') → pênaltis, conforme seção 4.
- [ ] Enviar `null` nos campos de prorrogação/`penaltyWinner` quando o palpite não é empate no momento correspondente.
- [ ] Exibir placar de prorrogação em `MatchResponse`/`PredictionResponse`/`MyPrediction` quando presente.
- [ ] Tratar os placares/prorrogação/`penaltyWinner`/`points` como possivelmente `null` (redação de palpite alheio).
- [ ] Mostrar breakdown de pontos usando `tournament.scoring` (feed) ou `TournamentSettingsResponse`.
