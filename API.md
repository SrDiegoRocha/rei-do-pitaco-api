# Rei do Pitaco API — Guia de integração

Documento de referência para integrar o frontend Angular com a API Rei do Pitaco. Cobre **todos** os endpoints, payloads, tipagens TypeScript, validações, regras de negócio relevantes pro cliente, status codes e fluxos.

---

## 1. Visão geral

### Base URL

```
http://localhost:8080
```

Em produção, vai mudar — manter como env var `API_BASE_URL`.

### Content-Type

Sempre `application/json` em requests com body. Responses sempre vêm em JSON UTF-8.

### Autenticação

A maior parte dos endpoints exige header:

```
Authorization: Bearer <accessToken>
```

Apenas os 3 endpoints de `/api/auth/*` aceitam sem token. Sem token em rota protegida → **403 Forbidden** (sem body útil) ou **401 Unauthorized** com `ApiError` quando o token está presente mas inválido/expirado.

### CORS

Hoje **não há configuração CORS no backend**. Em dev, configure `proxy.conf.json` no Angular apontando `/api` para `http://localhost:8080`. Quando for pra produção, o backend precisará habilitar CORS para o domínio do front.

### Formatos

- **Datas**: ISO 8601 UTC, ex. `2026-05-21T00:53:38.123Z`. Sempre instantes (`Instant`), nunca local time.
- **UUIDs**: strings v4, ex. `be243151-a22d-48b5-ba8d-4fa7747efb5a`. Todos os identificadores **públicos** (path params, response IDs) são UUID.
- **Numéricos**: `int`/`Integer` em Java vira `number` em JS.

---

## 2. Tratamento de erros

Toda resposta de erro segue o formato `ApiError`:

```ts
export interface ApiError {
  timestamp: string;          // ISO instant
  status: number;             // HTTP status code
  error: string;              // HTTP reason phrase ("Bad Request", "Conflict", etc.)
  message: string;            // mensagem técnica em inglês
  path: string;               // ex. "/api/auth/signin"
  fieldErrors: FieldError[] | null;
}

export interface FieldError {
  field: string;              // ex. "settings.matchLegMode"
  message: string;            // mensagem de validação (Bean Validation)
}
```

**Status codes esperados na aplicação inteira**

| Code | Quando aparece |
| ---- | -------------- |
| 200  | GET / PUT / POST de operação que retorna recurso |
| 201  | POST que cria recurso |
| 204  | DELETE bem-sucedido (sem body) |
| 400  | Payload inválido (`fieldErrors` populado), **ou** query/path param inválido (ex. `?scope=xpto`, UUID malformado) → `message` = `Invalid value for parameter 'X'` |
| 401  | Token ausente/inválido/expirado, credenciais erradas |
| 403  | Rota autenticada sem token, ou caller sem permissão (ex. não é owner do torneio) |
| 404  | Recurso não existe ou caller não tem acesso a ele (ex. time de outro dono) |
| 409  | Conflito de regra de negócio (lifecycle, duplicidade, lock, etc.) |
| 500  | Erro inesperado — mensagem genérica |

> Estratégia: tratar 401 globalmente no interceptor (tentar refresh, se falhar redirecionar pro login). 400/409 mostrar `message` no UI. 403/404 dependendo do contexto.

---

## 3. Enums

Todos os enums são `string` no JSON. Sempre **maiúsculas com underscore**.

```ts
export type Role = 'USER' | 'ADMIN';

export type TournamentPrivacy = 'PUBLIC' | 'PRIVATE';
export type TournamentStatus = 'DRAFT' | 'OPEN' | 'IN_PROGRESS' | 'FINISHED';
export type TournamentMemberRole = 'OWNER' | 'PARTICIPANT';
export type TournamentMemberStatus = 'ACTIVE' | 'LEFT' | 'BANNED';

export type TournamentPhaseType = 'ROUND_ROBIN' | 'KNOCKOUT' | 'GROUPS';
export type MatchLegMode = 'SINGLE' | 'TWO_LEGGED';
export type MatchGenerationMode = 'AUTOMATIC' | 'MANUAL';

export type TiebreakCriteria =
  | 'POINTS'
  | 'WINS'
  | 'GOAL_DIFFERENCE'
  | 'GOALS_FOR'
  | 'HEAD_TO_HEAD'
  | 'FEWEST_LOSSES';

export type MatchStatus = 'SCHEDULED' | 'COMPLETED' | 'CANCELLED';
export type MatchType = 'REGULAR' | 'THIRD_PLACE';
export type ZoneSelectionMode = 'ALL' | 'BEST_RANKED';

export type TeamType = 'CLUB' | 'NATIONAL_TEAM';
export type TeamScope = 'mine' | 'system' | 'all';   // query param de GET /api/teams
```

> No **corpo (JSON)** os enums vêm sempre em MAIÚSCULAS como acima. Em **query/path params** a conversão é **case-insensitive** (`?scope=mine` ou `?scope=MINE`, `?type=national_team` ou `?type=NATIONAL_TEAM` — tanto faz). Valor inexistente → **400** `Invalid value for parameter 'X'`.

---

## 4. Paginação (Spring Data `Page<T>`)

Algumas listagens retornam página, outras retornam array simples. Quando for página, o formato é:

```ts
export interface Page<T> {
  content: T[];
  number: number;             // página atual (0-indexed)
  size: number;
  numberOfElements: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
  empty: boolean;
  pageable: {
    pageNumber: number;
    pageSize: number;
    offset: number;
    paged: boolean;
    unpaged: boolean;
    sort: { empty: boolean; sorted: boolean; unsorted: boolean };
  };
  sort: { empty: boolean; sorted: boolean; unsorted: boolean };
}
```

Query params aceitos em rotas paginadas:

- `?page=0` (default 0)
- `?size=20` (default 20)
- `?sort=name,asc` ou `?sort=createdAt,desc` (múltiplos `sort` permitidos)

Onde indico abaixo `Page<TeamResponse>` é exatamente esse formato. Onde indico `TeamResponse[]` ou `List<TeamResponse>`, é array simples direto no body.

---

## 5. Autenticação (`/api/auth`)

Endpoints abertos (sem token). Retornam `AuthResponse` em sucesso.

### `AuthResponse`

```ts
export interface AuthResponse {
  accessToken: string;        // JWT HS256, validade 7 dias (10080 min)
  refreshToken: string;       // JWT HS256, vida longa (7d)
  tokenType: 'Bearer';
  expiresIn: number;          // TTL do accessToken em segundos
  user: UserSummary;
}

export interface UserSummary {
  id: string;                 // UUID público do usuário
  name: string;
  email: string;
  avatarUrl: string;          // gerado pelo backend (DiceBear) a partir do nome — ver nota abaixo
  role: Role;
  createdAt: string;          // ISO instant
}
```

> **Avatar (`avatarUrl`)**: o backend **gera** essa URL automaticamente a partir do **nome** do usuário (DiceBear). O usuário **não escolhe** avatar — mudou o nome, muda o avatar. O front só renderiza a URL como `<img src="...">`. Não é mais um campo de entrada. Sempre vem preenchido (não é mais `null`).

### `POST /api/auth/signup` → 201

Cria usuário (role `USER`, `active=true`) e devolve tokens.

```ts
export interface SignUpRequest {
  name: string;               // 2–120 chars
  email: string;              // formato válido, normalizado pra lowercase, único
  password: string;           // 8–100 chars
}
```

Erros específicos:
- **409** `Email already in use` — email já cadastrado.

### `POST /api/auth/signin` → 200

```ts
export interface SignInRequest {
  email: string;
  password: string;
}
```

Erros:
- **401** `Invalid email or password` — credenciais inválidas ou usuário inativo (`active=false`).

### `POST /api/auth/refresh` → 200

```ts
export interface RefreshTokenRequest {
  refreshToken: string;
}
```

Retorna novo par. **Rotação**: o refresh token apresentado é revogado nesta chamada — só vale **uma vez**. O endpoint só aceita tokens com claim `type=REFRESH`.

Erros:
- **401** `Invalid or expired token` (inclui token já usado/rotacionado ou revogado por logout)

### `POST /api/auth/logout` → 204

```ts
export interface RefreshTokenRequest { refreshToken: string; }   // mesmo payload do refresh
```

Revoga o refresh token informado (entra na denylist). Idempotente e lenient: mesmo um token inválido/expirado retorna **204**. Não precisa de access token. O access token atual continua válido até expirar (até 7 dias) — logout no cliente também descarta o access token.

### Estratégia de cliente recomendada

1. Guardar `accessToken` + `refreshToken` (cookie httpOnly via backend ou localStorage; localStorage é mais simples mas vulnerável a XSS — a escolha é tua).
2. Interceptor HTTP injeta `Authorization: Bearer <accessToken>` em toda request `/api/**` exceto `/api/auth/**`.
3. Interceptor de resposta detecta 401, tenta `POST /api/auth/refresh`; se sucesso, refaz a request original; se falhar, logout e redireciona pra login.
4. Decodificar o JWT no client é seguro (não é segredo) — útil pra exibir `name` antes de fazer outra chamada.

---

## 5.1 Perfil do usuário (`/api/users`)

Todos exigem access token. O usuário é resolvido pelo token (não há `{id}` na rota).

```ts
export interface UpdateProfileRequest {
  name: string;           // 2–120 chars (único campo editável; muda o avatar junto)
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;    // 8–100 chars
}
```

> Não há mais campo de avatar no perfil — o `avatarUrl` é derivado do nome (DiceBear). Ao editar o nome, o `avatarUrl` retornado já vem atualizado.

| Método | Path                     | Status | Body                    | Retorno        |
| ------ | ------------------------ | ------ | ----------------------- | -------------- |
| GET    | `/api/users/me`          | 200    | —                       | `UserResponse` |
| PUT    | `/api/users/me`          | 200    | `UpdateProfileRequest`  | `UserResponse` |
| PUT    | `/api/users/me/password` | 204    | `ChangePasswordRequest` | —              |

- `UserResponse` é o mesmo objeto `user` do `AuthResponse` (`id`, `name`, `email`, `avatarUrl`, `role`, `createdAt`). `avatarUrl` é a URL DiceBear derivada do nome.
- Email e role não são editáveis aqui; avatar não é editável (derivado do nome).
- `PUT /me/password` com `currentPassword` errado → **400** `Current password is incorrect`.
- Trocar a senha **não** invalida refresh tokens já emitidos (sem "logout de todos os dispositivos" ainda).

---

## 6. Times (`/api/teams`)

Todos os endpoints exigem auth. Há dois tipos de time:
- **Times do usuário** — criados por ele, editáveis/deletáveis só por ele.
- **Times do sistema** (`system: true`) — pré-cadastrados (ex.: as 48 seleções da Copa 2026), visíveis a **todos**, **não editáveis nem deletáveis**. O usuário pode usá-los nos torneios.

### `TeamResponse`

```ts
export interface TeamResponse {
  id: string;                 // UUID público
  name: string;
  shortName: string | null;   // 2–5 chars (nas seleções = sigla FIFA, ex. "BRA")
  badgeUrl: string | null;    // escudo (times de usuário / clubes); null nas seleções do sistema
  primaryColor: string;       // #RRGGBB
  secondaryColor: string;     // #RRGGBB
  system: boolean;            // true = time padrão do sistema (read-only)
  teamType: TeamType;         // 'CLUB' | 'NATIONAL_TEAM'
  countryCode: string | null; // código flagicons (ex. "br", "gb-eng"); preenchido nas seleções
  createdAt: string;
  updatedAt: string;
}

export type TeamType = 'CLUB' | 'NATIONAL_TEAM';
```

> **Imagem do time**: para `teamType === 'NATIONAL_TEAM'` use `countryCode` com o flagicons (`https://flagicons.lipis.dev`), ex. `<span class="fi fi-br"></span>` ou `.../flags/4x3/br.svg`. Para os demais (clubes/times de usuário) use `badgeUrl`.

### `POST /api/teams` → 201

```ts
export interface CreateTeamRequest {
  name: string;               // 2–80 chars
  shortName?: string | null;  // 2–5 chars (opcional)
  badgeUrl?: string | null;   // URL válida, até 500 chars (opcional)
  primaryColor: string;       // regex ^#[0-9a-fA-F]{6}$
  secondaryColor: string;     // regex ^#[0-9a-fA-F]{6}$
}
```

Erros:
- **400** com `fieldErrors` — payload inválido.
- **409** `You already have a team with this name` — nome já em uso pelo mesmo dono (case-insensitive). Note: outro usuário pode ter time com o mesmo nome.

Times criados pelo usuário nascem com `system: false` e `teamType: 'CLUB'`.

### `GET /api/teams` → 200 `Page<TeamResponse>`

Lista paginada. Aceita `page`, `size`, `sort` e dois filtros:

- `scope` (opcional): `mine` (padrão) = meus times; `system` = só os do sistema; `all` = meus + sistema.
- `type` (opcional): `CLUB` ou `NATIONAL_TEAM`.

Exemplos para os 3 grupos do front:
- Meus times: `GET /api/teams` (ou `?scope=mine`).
- Seleções do sistema: `GET /api/teams?scope=system&type=NATIONAL_TEAM`.
- Clubes do sistema: `GET /api/teams?scope=system&type=CLUB` (vazio por enquanto — só seleções foram cadastradas).

### `GET /api/teams/{id}` → 200 `TeamResponse`

`{id}` é o `publicId` (UUID). Times do sistema são acessíveis por qualquer usuário; times de usuário, só pelo dono. **404** se não existe ou é de outro usuário (não vazamos existência alheia).

### `PUT /api/teams/{id}` → 200 `TeamResponse`

Body = `CreateTeamRequest` (mesma estrutura para Update). Verificação de duplicidade só dispara se o nome mudou. **404** se não é dono. **403** `System teams cannot be modified or deleted` se for um time do sistema.

### `DELETE /api/teams/{id}` → 204

Soft delete (`active=false`). **404** se não é dono; **403** se for time do sistema. Times do sistema não podem ser deletados.

---

## 7. Torneios — CRUD principal (`/api/tournaments`)

### `TournamentResponse`

```ts
export interface TournamentResponse {
  id: string;                                 // UUID público
  name: string;
  description: string | null;
  inviteCode: string;                         // 6 chars alfanuméricos
  privacy: TournamentPrivacy;
  status: TournamentStatus;
  maxParticipants: number | null;
  maxTeams: number | null;
  owner: { id: string; name: string };
  settings: TournamentSettingsResponse;
  memberCount: number;                        // só conta ACTIVE
  teamCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface TournamentSettingsResponse {
  winPoints: number;
  drawPoints: number;
  lossPoints: number;
  exactScorePoints: number;                   // placar exato no tempo normal
  winnerPoints: number;                       // só o vencedor/empate no tempo normal
  wrongPoints: number;                        // erro no tempo normal
  extraTimeExactScorePoints: number;          // placar exato da prorrogação (default 2)
  extraTimeWinnerPoints: number;              // só o vencedor da prorrogação (default 1)
  penaltyWinnerPoints: number;                // acertar quem passa nos pênaltis (default 2)

  // Pick'em de fase (palpite de alto nível antes da fase começar — ver §20). Defaults 1.
  pickemQualifierPoints: number;              // time previsto que termina classificado
  pickemExactPositionPoints: number;          // time cravado na posição final exata
  pickemFirstPlacePoints: number;             // acertar o 1º da tabela/grupo
  pickemKoMatchupExactPoints: number;         // cravar os dois times de um confronto do mata-mata
  pickemKoMatchupPartialPoints: number;       // acertar pelo menos 1 time do confronto
  pickemChampionPoints: number;               // acertar o campeão
  pickemRunnerUpPoints: number;               // acertar o vice
  pickemThirdPlacePoints: number;             // acertar o 3º lugar

  tiebreakCriteria: TiebreakCriteria[];       // lista ordenada (ordem importa)
}
```

> Os três campos de mata-mata (`extraTimeExactScorePoints`, `extraTimeWinnerPoints`, `penaltyWinnerPoints`) **somam** ao componente do tempo normal — ver §19. Os oito campos `pickem*` valem para o **Pick'em de fase** (§20), não para palpites de partida.

### `POST /api/tournaments` → 201

Cria torneio em status `DRAFT`. Owner vira `TournamentMember` automático com role `OWNER` e status `ACTIVE`.

```ts
export interface CreateTournamentRequest {
  name: string;                               // 3–80 chars
  description?: string | null;                // até 500 chars
  privacy: TournamentPrivacy;
  maxParticipants?: number | null;            // ≥ 2
  maxTeams?: number | null;                   // ≥ 2
  settings: TournamentSettingsPayload;
}

export interface TournamentSettingsPayload {
  winPoints: number;                          // ≥ 0
  drawPoints: number;                         // ≥ 0
  lossPoints: number;                         // ≥ 0
  exactScorePoints: number;                   // ≥ 0
  winnerPoints: number;                       // ≥ 0
  wrongPoints: number;                        // ≥ 0

  // Mata-mata (opcionais, ≥ 0). Ausentes: no CREATE assumem os defaults 2/1/2;
  // no UPDATE preservam o valor atual (clientes que não enviam não resetam).
  extraTimeExactScorePoints?: number | null;  // default 2
  extraTimeWinnerPoints?: number | null;      // default 1
  penaltyWinnerPoints?: number | null;        // default 2

  tiebreakCriteria: TiebreakCriteria[];       // lista não vazia, sem duplicados
}
```

### `PUT /api/tournaments/{id}` → 200

Body = `UpdateTournamentRequest` (mesma estrutura de `CreateTournamentRequest`).

Regras de edição **por status**:

| Status | name/description | settings | privacy | maxParticipants/maxTeams |
| ------ | ---------------- | -------- | ------- | ------------------------ |
| DRAFT | sim | sim | sim | sim |
| OPEN | sim | sim | sim | sim |
| IN_PROGRESS | sim | sim | **não** (409) | sim |
| FINISHED | **não** (409) | **não** | **não** | **não** |

Diminuir `maxParticipants` abaixo do número atual de membros ativos, ou `maxTeams` abaixo do número de times vinculados → **409**.

Erros específicos:
- **400** `Tiebreak criteria must not contain duplicates` — lista com enums repetidos.

### `GET /api/tournaments/mine` → 200 `Page<TournamentResponse>`

Meus torneios (qualquer status, qualquer privacy).

### `GET /api/tournaments/public` → 200 `Page<TournamentResponse>`

Públicos com status `OPEN` ou `IN_PROGRESS`. Privados nunca aparecem aqui.

### `GET /api/tournaments/joined` → 200 `Page<TournamentResponse>`

Torneios onde sou `TournamentMember` com status `ACTIVE`.

### `GET /api/tournaments/{id}` → 200 `TournamentResponse`

Detalhe. Acessível para: owner, qualquer member, ou qualquer um se o torneio for PUBLIC OPEN/IN_PROGRESS. Senão **404**.

### `POST /api/tournaments/{id}/status` → 200

Avança o status linearmente. Owner-only.

```ts
export interface ChangeStatusRequest {
  targetStatus: TournamentStatus;
}
```

Transições válidas:
- `DRAFT → OPEN`
- `OPEN → IN_PROGRESS`
- `IN_PROGRESS → FINISHED`

Qualquer outra (`OPEN → DRAFT`, `DRAFT → IN_PROGRESS`, etc.) → **409** `Cannot transition tournament from X to Y`.

### `POST /api/tournaments/{id}/invite-code/regenerate` → 200

Gera novo `inviteCode` de 6 chars. Owner-only. O código anterior **deixa de funcionar imediatamente**.

### `DELETE /api/tournaments/{id}` → 204

Soft delete. Owner-only.

### `POST /api/tournaments/join` → 200 `TournamentResponse`

Entra como participante via código de convite. Funciona em torneios `OPEN` ou `IN_PROGRESS`.

```ts
export interface JoinTournamentRequest {
  inviteCode: string;         // 6 chars
}
```

Comportamento:
- Se for primeira vez do user no torneio: cria `TournamentMember` com role `PARTICIPANT` e status `ACTIVE`.
- Se já saiu antes (status `LEFT`): reativa.
- Se já é `ACTIVE`: **409** `You are already a member of this tournament`.
- Se foi banido (status `BANNED`): **403** `You are banned from this tournament` — permanente.

Outros erros:
- **404** `Tournament not found` — código inexistente ou torneio soft-deletado.
- **409** `Cannot modify tournament in status DRAFT/FINISHED: tournament is not accepting members` — torneio fora de OPEN/IN_PROGRESS.
- **409** `Tournament is full: max participants reached` — atingiu `maxParticipants`.

---

## 8. Membros do torneio (`/api/tournaments/{tournamentId}/members`)

### `TournamentMemberResponse`

```ts
export interface TournamentMemberResponse {
  userId: string;             // UUID público
  name: string;
  avatarUrl: string;          // DiceBear, derivado do nome
  role: TournamentMemberRole;
  status: TournamentMemberStatus;
  joinedAt: string;
  leftAt: string | null;
  bannedAt: string | null;
}
```

### `GET /api/tournaments/{tournamentId}/members` → 200 `Page<TournamentMemberResponse>`

Lista paginada de todos os membros (incluindo LEFT e BANNED).

### `DELETE /api/tournaments/{tournamentId}/members/me` → 204

Sair voluntariamente. Status vira `LEFT`, `leftAt` registrado. Pode rejoin com o mesmo invite code.

Erros:
- **409** `Tournament owner cannot leave their own tournament` — owner não pode usar este endpoint.

### `DELETE /api/tournaments/{tournamentId}/members/{userId}` → 204

Banir um membro. Owner-only. Status vira `BANNED`, permanente.

Erros:
- **403** `Only the tournament owner can perform this action`
- **409** `Tournament owner cannot leave their own tournament` — owner não pode se autobanir (sim, mesma mensagem).

---

## 9. Times vinculados ao torneio (`/api/tournaments/{tournamentId}/teams`)

### `TournamentTeamResponse`

```ts
export interface TournamentTeamResponse {
  teamId: string;             // UUID público do Team
  name: string;
  shortName: string | null;
  badgeUrl: string | null;
  primaryColor: string;
  secondaryColor: string;
  system: boolean;            // true = time do sistema
  teamType: TeamType;         // 'CLUB' | 'NATIONAL_TEAM'
  countryCode: string | null; // flagicons; preenchido nas seleções
  addedAt: string;
}
```

### `GET /api/tournaments/{tournamentId}/teams` → 200 `Page<TournamentTeamResponse>`

### `POST /api/tournaments/{tournamentId}/teams/{teamId}` → 201

Vincula ao torneio um time do **próprio owner** **ou** um **time do sistema** (seleção/clube padrão). Owner-only.

Erros:
- **403** `You can only add your own teams to a tournament` — tentou vincular time de **outro usuário** (times do sistema são permitidos).
- **409** `Team is already part of this tournament` — duplicado.
- **409** `Tournament is full: max teams reached` — atingiu `maxTeams`.
- **409** `Cannot modify tournament in status IN_PROGRESS/FINISHED: teams can only be changed in DRAFT or OPEN`.

### `DELETE /api/tournaments/{tournamentId}/teams/{teamId}` → 204

Desvincula. Mesmas regras de status do POST.

---

## 10. Fases do torneio (`/api/tournaments/{tournamentId}/phases`)

Cada torneio é uma **sequência ordenada** de phases. Cada phase tem seu próprio formato (round-robin / knockout / grupos) e suas próprias regras de geração e ida-volta.

### `PhaseResponse`

```ts
export interface PhaseResponse {
  id: string;                 // UUID público
  name: string;
  position: number;           // 0-indexed
  phaseType: TournamentPhaseType;
  matchLegMode: MatchLegMode;
  matchGenerationMode: MatchGenerationMode;
  playsInsideGroupOnly: boolean | null;  // só relevante em GROUPS
  hasThirdPlace: boolean;                // só relevante em KNOCKOUT
  groupCount: number;
  teamCount: number;
  finalizedAt: string | null;            // ISO instant; null = fase ainda não finalizada
  createdAt: string;
  updatedAt: string;
}
```

`finalizedAt` é preenchido quando a fase passa pelo `POST .../finalize` com sucesso (e re-escrito se o finalize rodar de novo). Use-o para distinguir "pronta pra finalizar" de "já finalizada": esconder o botão de finalizar, mostrar banner "Fase finalizada em {data}", e travar criação/geração de partidas na UI.

### `POST /api/tournaments/{tournamentId}/phases` → 201

Cria phase. Se for a phase de posição `0` (primeira), **auto-popula** o `PhaseTeam` com todos os `TournamentTeam` do torneio.

```ts
export interface CreatePhaseRequest {
  name: string;                                  // 1–60 chars
  phaseType: TournamentPhaseType;
  matchLegMode: MatchLegMode;
  matchGenerationMode: MatchGenerationMode;
  playsInsideGroupOnly?: boolean | null;         // só usado em GROUPS
  hasThirdPlace?: boolean | null;                // só usado em KNOCKOUT
}
```

Para outros `phaseType`, os campos que não fazem sentido são ignorados pelo backend (sempre vêm `null` ou `false` no response).

### `GET /api/tournaments/{tournamentId}/phases` → 200 `PhaseResponse[]`

Lista ordenada por `position` ascendente.

### `GET /api/tournaments/{tournamentId}/phases/{phaseId}` → 200 `PhaseResponse`

### `PUT /api/tournaments/{tournamentId}/phases/{phaseId}` → 200

Body = `UpdatePhaseRequest` (mesma estrutura de `CreatePhaseRequest`).

### `POST /api/tournaments/{tournamentId}/phases/{phaseId}/move` → 200

Reordena phases. Service faz o shift automático das demais para manter posições contíguas.

```ts
export interface MovePhaseRequest {
  position: number;           // ≥ 0
}
```

### `DELETE /api/tournaments/{tournamentId}/phases/{phaseId}` → 204

Hard delete. Bloqueado quando há matches associados (**409** `Cannot remove phase because it has matches attached`).

### Lifecycle vs status do torneio

- **DRAFT/OPEN**: cria/edita/deleta/reordena phases livremente.
- **IN_PROGRESS**: estrutura trava → **409** `Phase structure is locked while tournament is IN_PROGRESS`.
- **FINISHED**: read-only.

---

## 11. Grupos da fase (`/api/tournaments/{tid}/phases/{pid}/groups`)

Só válido em phase do tipo `GROUPS`.

### `PhaseGroupResponse`

```ts
export interface PhaseGroupResponse {
  id: string;                 // UUID público
  name: string;
  position: number;
  teamCount: number;
  createdAt: string;
  updatedAt: string;
}
```

### `POST /api/tournaments/{tid}/phases/{pid}/groups` → 201

```ts
export interface CreatePhaseGroupRequest {
  name: string;               // 1–40 chars, livre
}
```

Erros:
- **409** `Groups can only be added to a phase of type GROUPS` — phase não é GROUPS.
- **409** `This phase already has a group with that name` — nome duplicado (case-insensitive) na mesma phase.

### `GET /api/tournaments/{tid}/phases/{pid}/groups` → 200 `PhaseGroupResponse[]`

### `PUT /api/tournaments/{tid}/phases/{pid}/groups/{groupId}` → 200

```ts
export interface UpdatePhaseGroupRequest {
  name: string;
}
```

### `DELETE /api/tournaments/{tid}/phases/{pid}/groups/{groupId}` → 204

Hard delete. Bloqueado se há matches no grupo (**409** `Cannot remove group because it has matches attached`).

---

## 12. Times da fase (`/api/tournaments/{tid}/phases/{pid}/teams`)

Quais times participam **daquela phase**. Em phase 0, é auto-populado com os `TournamentTeam`. Em phases seguintes, admin adiciona manualmente (ou são propagados pelo `finalize`).

### `PhaseTeamResponse`

```ts
export interface PhaseTeamResponse {
  teamId: string;             // UUID público do Team
  teamName: string;
  shortName: string | null;
  badgeUrl: string | null;
  primaryColor: string;
  secondaryColor: string;
  teamType: TeamType;         // 'CLUB' | 'NATIONAL_TEAM'
  countryCode: string | null; // flagicons; preenchido nas seleções
  groupId: string | null;     // UUID do PhaseGroup; null em ROUND_ROBIN/KNOCKOUT
  groupName: string | null;
  addedAt: string;
}
```

### `GET /api/tournaments/{tid}/phases/{pid}/teams` → 200 `PhaseTeamResponse[]`

### `POST /api/tournaments/{tid}/phases/{pid}/teams/{teamId}` → 201

Adiciona time à phase. O time precisa estar em `TournamentTeam` (vinculado ao torneio).

Erros:
- **409** `Team is not part of this tournament` — time não está no roster do torneio.
- **409** `Team is already part of this tournament` — duplicado na phase.

### `PUT /api/tournaments/{tid}/phases/{pid}/teams/{teamId}` → 200

Atribui (ou desatribui com `groupId: null`) o time a um grupo.

```ts
export interface MovePhaseTeamRequest {
  groupId: string | null;     // UUID do PhaseGroup ou null
}
```

Erros:
- **404** `Team is not in this phase`
- **404** `Group not found`

### `DELETE /api/tournaments/{tid}/phases/{pid}/teams/{teamId}` → 204

Remove time da phase. Bloqueado se há matches envolvendo o time na phase (**409**).

### `POST /api/tournaments/{tid}/phases/{pid}/teams/draw` → 200 `PhaseTeamResponse[]`

Sorteia (round-robin de distribuição com `SecureRandom`) os `PhaseTeam`s **sem grupo** entre os grupos existentes da phase.

Erros:
- **409** `BEST_RANKED zones are only valid on GROUPS phases` — phase não é GROUPS.
- **409** `Phase has no groups configured to draw teams into` — sem grupos.

---

## 13. Zonas (`/api/tournaments/{tid}/phases/{pid}/zones`)

Faixas de posição na tabela da phase com uma regra: o que acontece com os times daquela faixa quando a phase termina (avança pra outra phase ou cai no vácuo).

### `ZoneResponse`

```ts
export interface ZoneResponse {
  id: string;                 // UUID público
  name: string;
  fromPosition: number;
  toPosition: number;
  selectionMode: ZoneSelectionMode;
  bestRankedCount: number | null;
  nextPhaseId: string | null;
  nextPhaseName: string | null;
  position: number;
  createdAt: string;
  updatedAt: string;
}
```

### `POST /api/tournaments/{tid}/phases/{pid}/zones` → 201

```ts
export interface CreateZoneRequest {
  name: string;                       // 1–60 chars
  fromPosition: number;               // ≥ 1
  toPosition: number;                 // ≥ fromPosition
  selectionMode: ZoneSelectionMode;
  bestRankedCount?: number | null;    // obrigatório se BEST_RANKED
  nextPhaseId?: string | null;        // UUID da phase destino, ou null pra "eliminado"
}
```

Validações:
- `fromPosition <= toPosition` (senão **409**).
- Não pode sobrepor outra zone da mesma phase (**409** `zone overlaps with existing zone 'X'`).
- `BEST_RANKED` exige: phase `GROUPS`, `fromPosition == toPosition`, `bestRankedCount > 0` e `≤ groupCount`.
- `nextPhaseId`, se preenchido, precisa pertencer ao mesmo torneio e ter `position` maior que a phase atual (**409** `nextPhase must come after the current phase`).

### `GET /api/tournaments/{tid}/phases/{pid}/zones` → 200 `ZoneResponse[]`

Ordenado por `position` ascendente.

### `PUT /api/tournaments/{tid}/phases/{pid}/zones/{zoneId}` → 200

Body = `UpdateZoneRequest` (mesma estrutura de `CreateZoneRequest`).

### `DELETE /api/tournaments/{tid}/phases/{pid}/zones/{zoneId}` → 204

### Diferenças de lifecycle

Zonas podem ser criadas/editadas/deletadas em `DRAFT`, `OPEN` **e** `IN_PROGRESS`. Só travam em `FINISHED`. (Diferente da estrutura — phases/groups/phaseTeam — que trava em IN_PROGRESS.)

---

## 14. Partidas (`/api/tournaments/{tid}/phases/{pid}/matches`)

### `MatchResponse`

```ts
export interface MatchResponse {
  id: string;                 // UUID público
  phaseId: string;
  groupId: string | null;
  groupName: string | null;
  round: number;
  tieId: string;              // UUID que agrupa pernas de ida e volta
  matchType: MatchType;       // REGULAR | THIRD_PLACE
  homeTeam: TeamRef;
  awayTeam: TeamRef;
  scheduledAt: string | null;
  homeScore: number | null;
  awayScore: number | null;
  homeExtraTimeScore: number | null;  // placar da prorrogação (KO jogo único); null se não houve. Cumulativo.
  awayExtraTimeScore: number | null;
  homePenalties: number | null;   // só preenchido em disputa de pênaltis (KNOCKOUT)
  awayPenalties: number | null;
  penaltyShootoutEligible: boolean; // empate AQUI pode ir aos pênaltis no palpite (ver abaixo)
  aggregateBeforeHome: number;     // gols do mandante DESTA partida nas pernas anteriores do tie
  aggregateBeforeAway: number;     // idem visitante; 0 em jogo único / ida ainda não concluída
  status: MatchStatus;
  createdAt: string;
  updatedAt: string;
}

export interface TeamRef {
  id: string;
  name: string;
  shortName: string | null;
  badgeUrl: string | null;
  primaryColor: string | null;    // hex, ex. "#10B981"
  secondaryColor: string | null;
  teamType: TeamType;             // 'CLUB' | 'NATIONAL_TEAM'
  countryCode: string | null;     // flagicons (ex. "br"); preenchido nas seleções
}
```

> `TeamRef` é usado em `MatchResponse.homeTeam/awayTeam`, nas pernas do bracket (`BracketTie.legs`) e no `BracketTie.homeTeam/awayTeam/winner`. As cores vêm do `Team` (mesmas do CRUD de times).

**Campos de apoio ao palpite de pênaltis** (`penaltyShootoutEligible`, `aggregateBeforeHome`, `aggregateBeforeAway`):
- `penaltyShootoutEligible` = `true` quando um empate **neste confronto** pode ir aos pênaltis no palpite: **jogo único** de KO (`KNOCKOUT` + `matchLegMode=SINGLE`), ou a **perna de volta** (a de maior `round`) de um `TWO_LEGGED`. `false` nos demais (grupos, pontos corridos, **perna de ida**, fora de mata-mata).
- `aggregateBeforeHome`/`aggregateBeforeAway` = gols já marcados nas **pernas anteriores** do confronto (mesmo `tieId`, partidas `COMPLETED` de `round` menor), **orientados ao mandante/visitante DESTA partida** (não ao da 1ª perna como no `BracketTie`). `0/0` em jogo único, ou enquanto a ida não foi concluída.
- **Uso no front**: o seletor de "quem avança" aparece (e o `penaltyWinner` é obrigatório) quando `penaltyShootoutEligible && (aggregateBeforeHome + homeScorePalpitado) === (aggregateBeforeAway + awayScorePalpitado)` — ou seja, quando o **agregado** (pernas anteriores + palpite desta) termina empatado. Em jogo único, como o agregado anterior é `0/0`, isso equivale ao empate do placar da própria partida.
- Exemplo: ida `Cruzeiro 2 x 1 Flamengo`; a volta é `Flamengo x Cruzeiro` → no `MatchResponse` da volta, `aggregateBeforeHome` (Flamengo) = `1`, `aggregateBeforeAway` (Cruzeiro) = `2`.

### `POST /api/tournaments/{tid}/phases/{pid}/matches` → 201

```ts
export interface CreateMatchRequest {
  homeTeamId: string;             // UUID do Team (precisa estar em PhaseTeam)
  awayTeamId: string;             // distinto do home
  round: number;                  // ≥ 0
  groupId?: string | null;        // obrigatório em GROUPS phase; proibido fora
  tieId?: string | null;          // opcional; sistema gera se omitido
  scheduledAt?: string | null;    // ISO instant; opcional
  matchType?: MatchType | null;   // default REGULAR; THIRD_PLACE só em KNOCKOUT
}
```

Validações cruzadas (todas retornam **409**):
- `A team cannot play against itself` (homeTeamId == awayTeamId)
- `home/away team is not registered in this phase` (time não em PhaseTeam)
- `groupId is required for GROUPS phase` / `groupId only applies to GROUPS phase`
- `home/away team is not in the specified group`
- `A team cannot play twice in the same round` (mandante ou visitante já joga essa rodada na phase)
- `tieId already belongs to a different phase`
- `tie legs must have inverted home/away teams`
- `tie legs must be in different rounds`
- `a tie can have at most two legs`
- `THIRD_PLACE matches are only allowed in KNOCKOUT phases`

### `GET /api/tournaments/{tid}/phases/{pid}/matches` → 200 `MatchResponse[]`

Query opcional:
- `?round=N` — filtra por rodada
- `?groupId=UUID` — filtra por grupo

(Os dois são mutuamente exclusivos no service — só um é considerado por chamada.)

### `GET /api/tournaments/{tid}/phases/{pid}/matches/{matchId}` → 200 `MatchResponse`

### `PUT /api/tournaments/{tid}/phases/{pid}/matches/{matchId}` → 200

Atualiza dados de agendamento. Bloqueado se match já está `COMPLETED` (**409** `Cannot edit match scheduling after result is set; clear result first`).

```ts
export interface UpdateMatchRequest {
  homeTeamId: string;
  awayTeamId: string;
  round: number;
  groupId?: string | null;
  scheduledAt?: string | null;
  matchType?: MatchType | null;   // default REGULAR; THIRD_PLACE só em KNOCKOUT
}
```

> Note: `tieId` **não** é editável após criação.

### `PUT /api/tournaments/{tid}/phases/{pid}/matches/{matchId}/result` → 200

Lança ou edita o resultado. Após salvar, **recalcula pontos de todos os palpites** vinculados ao match.

```ts
export interface SetMatchResultRequest {
  homeScore: number;          // ≥ 0  (tempo normal)
  awayScore: number;          // ≥ 0
  homeExtraTimeScore?: number | null;  // prorrogação: opcional, ambos juntos, ≥ 0 e ≥ placar do tempo normal
  awayExtraTimeScore?: number | null;
  homePenalties?: number | null;   // pênaltis: opcional, ambos juntos, ≥ 0 e diferentes
  awayPenalties?: number | null;
}
```

Fluxo em KO jogo único: `homeScore`/`awayScore` (90'). Se empatou → informa `homeExtraTimeScore`/`awayExtraTimeScore`. Se a prorrogação empatou → informa `homePenalties`/`awayPenalties`.

**Prorrogação** (extra time — só mata-mata de **jogo único**, `KNOCKOUT` + `matchLegMode = SINGLE`):
- **Cumulativa**: inclui os gols do tempo normal, então nunca pode ser menor que o placar do 90' por time (**409** `Extra-time score cannot be lower than the regular-time score`).
- Vêm em par (**409** `Both extra-time scores must be provided together`).
- Só em KO jogo único (**409** `Extra time only applies to single-leg KNOCKOUT matches`) e só quando o 90' empatou (**409** `Extra time only applies when regular time ended in a draw`).
- Quando lançada, é o **placar decisivo**: reflete no agregado/`winner` do bracket e na geração da próxima rodada de KO.

**Pênaltis** (desempate de mata-mata):
- Só em phase `KNOCKOUT` (**409** `Penalties only apply to KNOCKOUT phases`).
- Vêm em par (**409** `Both penalty scores must be provided together`) e não empatam (**409** `Penalty shootout cannot end in a draw`).
- Em jogo único com prorrogação lançada, só são aceitos se a prorrogação empatou (**409** `Penalties only apply when extra time ended in a draw`).
- Só decidem o confronto quando o **placar decisivo/agregado está empatado** (em jogo único = prorrogação se houve, senão 90'; em TWO_LEGGED, lançar na perna decisiva). Sem regra de gol fora de casa. Refletem no `winner` do bracket e destravam a geração da próxima rodada de KO.

Validações:
- **409** `Results can only be set while tournament is IN_PROGRESS`
- **409** `Cannot set result on a cancelled match`
- **409** `Results can only be set after the prediction deadline (scheduledAt)` — **só quando a partida tem `scheduledAt`** e `now < scheduledAt`. Partida **sem** `scheduledAt` pode ter o resultado lançado a qualquer momento (lançar é o que fecha a janela de palpites). Não é mais obrigatório marcar data antes de lançar o resultado.

### `PUT /api/tournaments/{tid}/phases/{pid}/matches/{matchId}/cancel` → 200

Marca como `CANCELLED` e zera placares. Os palpites do match perdem todos os pontos (mas mantém os placares pra histórico).

### `DELETE /api/tournaments/{tid}/phases/{pid}/matches/{matchId}` → 204

Hard delete. Bloqueado em torneio `FINISHED`.

### `GET /api/tournaments/{tournamentId}/matches` → 200 `MatchResponse[]`

> **Atenção ao path**: nível **torneio**, sem `/phases/{pid}`. Lista **todas** as partidas atravessando todas as `TournamentPhase`s do torneio.

Sem paginação, sem filtros (`round`/`groupId` não se aplicam aqui — use o endpoint por phase pra filtrar).

Ordenação aplicada no banco:

1. `phase.position` ASC — fase 1 antes da fase 2, etc.
2. Dentro de cada fase: `COALESCE(scheduledAt, createdAt)` ASC. Match com horário marcado entra no fluxo cronológico; sem horário, o `createdAt` segura o lugar.
3. Tie-break por `createdAt` ASC pra estabilidade.

Acesso: aplica o controle de visibilidade do torneio (owner, member ACTIVE, ou PUBLIC não-DRAFT). Mesmo padrão de `GET /ranking` e do list por phase.

Erros:
- **404** `Tournament not found` — torneio inexistente, soft-deletado, ou inacessível para o requester.

Útil pra telas tipo "calendário do torneio" ou "todas as partidas em ordem", sem precisar paginar por phase no cliente.

---

## 14.1 Feed pessoal de partidas (`/api/users/me/matches`)

Tela inicial do app: lista **todas as partidas de todos os torneios em que o usuário participa**, em ordem cronológica, para um único scroll vertical centrado no "hoje" (rolar pra cima → partidas passadas; pra baixo → partidas futuras).

### `GET /api/users/me/matches` → 200 `UserMatchResponse[]`

Sem body. Retorna a lista já ordenada pelo banco. **Sem query params, devolve a lista completa** (comportamento original, retrocompatível). Os params abaixo são **opcionais** e servem para paginar por janela de data.

**Query params (todos opcionais):**

| Param | Tipo | Descrição |
| --- | --- | --- |
| `from` | ISO instant | Recorta por `scheduledAt >= from`. Ex.: `2026-06-11T00:00:00Z`. |
| `to` | ISO instant | Recorta por `scheduledAt < to` (limite **exclusivo**). |
| `limit` | int | Teto de itens retornados. Omisso (ou `<= 0`) → **sem teto** (lista inteira). |

A janela é semiaberta `[from, to)`, então páginas adjacentes (`to` de uma = `from` da próxima) não duplicam nem pulam partidas. Cada bound é independente — dá pra mandar só `from`, só `to`, ou os dois. Valor malformado em qualquer param → **400** `Invalid value for parameter '<nome>'`.

**Sugestão de uso pelo front (paginação de rede):**
- 1ª carga centrada no hoje: `from = now - 2d`, `to = now + 7d`.
- scroll pra baixo: próximo `from = to anterior` (e novo `to` mais à frente).
- scroll pra cima: `to = from anterior`, `from = to - Nd`.

**Escopo (quais partidas entram):**
- De torneios **ativos** (não soft-deletados) onde o usuário é **membro `ACTIVE`**. Isso inclui os torneios em que ele é **dono** (o dono é membro auto-ativo). Quem saiu (`LEFT`) ou foi banido (`BANNED`) não vê as partidas daquele torneio.
- **Apenas partidas com `scheduledAt` definido.** Partidas sem horário marcado **não aparecem** (não têm lugar numa timeline por data) — diferente do `GET /api/tournaments/{id}/matches`, que traz todas.
- Cobre todas as fases de todos esses torneios, misturadas e ordenadas por data.

**Ordenação (aplicada no banco):**
1. `scheduledAt` ASC — cronológico puro, atravessando torneios e fases.
2. `createdAt` ASC — desempate estável quando dois jogos têm o mesmo horário.

> Como tudo vem ordenado por `scheduledAt`, o front acha o "hoje" buscando o primeiro item com `match.scheduledAt >= now` e ancora o scroll ali.

**Acesso:** basta estar autenticado. Não há `TournamentAccessGuard` aqui — o próprio escopo (membro ACTIVE) já é o controle de acesso. Token ausente/inválido → **401/403** padrão.

**Tipagem:**

```ts
export interface UserMatchResponse {
  match: MatchResponse;          // mesmo payload da §14 (times, placar, status, pênaltis, round, tieId...)
  tournament: TournamentRef;
  phase: PhaseRef;
  group: GroupRef | null;        // null fora de fase GROUPS
  myPrediction: MyPrediction | null; // null se o usuário ainda não palpitou nesta partida
}

export interface TournamentRef {
  id: string;                    // UUID
  name: string;
  privacy: 'PUBLIC' | 'PRIVATE';
  status: 'DRAFT' | 'OPEN' | 'IN_PROGRESS' | 'FINISHED';
  scoring: ScoringRef;           // pontuação de palpite do torneio (chip de pontos por faixa)
}

export interface ScoringRef {
  exactScorePoints: number;         // placar exato (tempo normal)
  winnerPoints: number;             // só o vencedor/empate (tempo normal)
  wrongPoints: number;              // erro (tempo normal)
  extraTimeExactScorePoints: number; // placar exato da prorrogação
  extraTimeWinnerPoints: number;     // só o vencedor da prorrogação
  penaltyWinnerPoints: number;       // acertar quem passa nos pênaltis
}

export interface PhaseRef {
  id: string;                    // UUID
  name: string;                  // ex. "Fase de Grupos", "Oitavas"
  position: number;              // ordem da fase no torneio (0-based)
  phaseType: 'ROUND_ROBIN' | 'KNOCKOUT' | 'GROUPS';
  matchLegMode: 'SINGLE' | 'TWO_LEGGED';
}

export interface GroupRef {
  id: string;                    // UUID
  name: string;                  // ex. "Grupo A"
  position: number;
}

export interface MyPrediction {
  id: string;                    // UUID do palpite
  homeScore: number;
  awayScore: number;
  homeExtraTimeScore: number | null; // placar palpitado da prorrogação (KO jogo único); null se não palpitou
  awayExtraTimeScore: number | null;
  penaltyWinner: 'HOME' | 'AWAY' | null; // só em palpite de empate em KO
  points: number;                // pontos já apurados (0 enquanto a partida não fecha)
}
```

**Notas para o front:**
- O `match` é o **mesmo** `MatchResponse` dos outros endpoints — `round` (a "etapa/rodada"), `phaseId`, `groupId`/`groupName`, `homeTeam`/`awayTeam` (com cores, escudo, `countryCode` pra bandeira), `homeScore`/`awayScore`, `status`, pênaltis etc. vivem lá dentro. Os refs `tournament`/`phase`/`group` adicionam os **nomes/tipos** que o `MatchResponse` não carrega, pra você montar o cabeçalho do card ("Copa da Firma · Fase de Grupos · Grupo A · Rodada 2") sem chamadas extras.
- `myPrediction` é o palpite **do próprio usuário** — nunca redigido (diferente da visibilidade dos palpites alheios na §17). `null` = ainda não palpitou; mostre um CTA "Palpitar". Para gravar/editar, use o `PUT` de palpite da §17 com o `tournament.id` e o `match.id`.
- `tournament.scoring` traz a pontuação de palpite do torneio — tempo normal (`exactScorePoints`/`winnerPoints`/`wrongPoints`) e mata-mata (`extraTimeExactScorePoints`/`extraTimeWinnerPoints`/`penaltyWinnerPoints`) — para o chip de pontos por faixa ser colorido igual à aba de partidas do torneio, sem fallback.
- Para volumes maiores, use `from`/`to`/`limit` para paginar por rede em vez de baixar tudo e janelar no client.

### `GET /api/users/me/matches/pending-count` → 200 `PendingPredictionsCountResponse`

Agregado leve para o badge "X jogos esperando seu pitaco" no menu/tela inicial. Sem params, sem body.

```ts
export interface PendingPredictionsCountResponse {
  count: number;
}
```

Conta partidas que satisfazem **todas** as condições:
- `status = SCHEDULED` e `scheduledAt` no **futuro** (`now < scheduledAt`) — janela de palpite ainda aberta;
- de torneios **`IN_PROGRESS`** e ativos onde o usuário é **membro `ACTIVE`**;
- nas quais o usuário **ainda não palpitou**.

Acesso: basta estar autenticado (escopado ao próprio usuário). É mais barato que baixar a lista só pra contar — combina com o uso paginado do feed acima.

---

## 15. Geração automática de partidas

### `POST /api/tournaments/{tid}/phases/{pid}/matches/generate` → 201 `MatchResponse[]`

Owner-only. Sem body.

**Pré-condições**:
- Torneio não pode estar `FINISHED`.
- `phase.matchGenerationMode` precisa ser `AUTOMATIC` (senão **409** `Phase has matchGenerationMode=MANUAL; cannot auto-generate`).
- Em `ROUND_ROBIN`/`GROUPS`: phase precisa estar **vazia** de matches.
- Em `KNOCKOUT`: phase vazia gera round 1; com matches já presentes, gera a próxima rodada a partir dos vencedores.

**Algoritmo**:
- `ROUND_ROBIN`/`GROUPS`: algoritmo de círculo (Berger). Shuffle inicial com `SecureRandom`, bye para N ímpar, `N-1` rodadas em `SINGLE` e `2*(N-1)` em `TWO_LEGGED` (ida e volta com `tieId` comum). Em `GROUPS`, executa por grupo.
- `KNOCKOUT`: requer **potência de 2** de times na primeira chamada (**409** `KNOCKOUT requires a power of 2 of teams (got N)`). Emparelha 1×último, 2×penúltimo, etc. Chamadas seguintes detectam vencedores da rodada anterior (single ou agregado em TWO_LEGGED) e geram a próxima.
- `hasThirdPlace=true` em KNOCKOUT: quando estiver gerando a rodada final, cria também a disputa de 3º lugar entre os 2 perdedores das semifinais.

**Outros erros possíveis** (todos 409):
- `Phase already has matches; clear them before generating` (RR/GROUPS)
- `Phase needs at least 2 teams to generate matches`
- `GROUPS phase has no groups configured`
- `Team 'X' is not assigned to any group`
- `Group 'X' needs at least 2 teams`
- `Previous round still has unfinished matches` (KO)
- `Tie X has no winner (draw on aggregate); resolve manually` (KO TWO_LEGGED com empate no agregado)
- `Phase already has a champion; no more rounds to generate`

---

## 16. Classificação e finalização

### `StandingsResponse`

```ts
export interface StandingsResponse {
  phaseId: string;
  groups: GroupStandings[];   // 1 entrada em ROUND_ROBIN; N em GROUPS
}

export interface GroupStandings {
  groupId: string | null;     // null se phase não é GROUPS
  groupName: string | null;
  rows: StandingRow[];
}

export interface StandingRow {
  position: number;           // 1-indexed
  teamId: string;
  teamName: string;
  shortName: string | null;
  badgeUrl: string | null;
  teamType: TeamType;         // 'CLUB' | 'NATIONAL_TEAM'
  countryCode: string | null; // flagicons; preenchido nas seleções
  played: number;
  wins: number;
  draws: number;
  losses: number;
  goalsFor: number;
  goalsAgainst: number;
  goalDifference: number;
  points: number;
  // Desfecho da zona de avanço para esta posição (enriquecido pelo backend):
  zoneId: string | null;        // null se nenhuma zona cobre a posição
  zoneName: string | null;      // ex.: "Classificado", "Repescagem", "Eliminado"
  nextPhaseId: string | null;   // destino do avanço; null = zona de descarte (não avança)
  nextPhaseName: string | null;
  qualifies: boolean;           // true = o time avança (resolve BEST_RANKED de forma autoritativa)
}
```

### `GET /api/tournaments/{tid}/phases/{pid}/standings` → 200 `StandingsResponse`

Calculado **on-demand** a partir dos matches `COMPLETED` da phase, aplicando `winPoints`/`drawPoints`/`lossPoints` do settings e ordenando pelos `tiebreakCriteria` do torneio (na ordem definida). `HEAD_TO_HEAD` aplica-se dentro do grupo apenas (não entre grupos).

Matches `SCHEDULED` ou `CANCELLED` não contam.

**Enriquecimento por zona**: cada linha já vem com o desfecho da sua posição em relação às `TournamentZone` da fase, então o front não precisa cruzar standings + zonas nem reproduzir a lógica de "melhores N". Para zonas `ALL`, `qualifies` é true quando a posição cai numa zona com `nextPhase`. Para zonas `BEST_RANKED` (ex.: 8 melhores 3ºs), o backend ranqueia os candidatos entre os grupos e marca `qualifies` só nos selecionados — é uma **projeção provisória** enquanto a fase não terminou (recalcula a cada chamada conforme os resultados entram). Posição sem zona → todos os campos de zona `null`/`false`.

Válido apenas para `ROUND_ROBIN` e `GROUPS`. Em phase `KNOCKOUT` retorna **409** `Standings are not available for KNOCKOUT phases; use /bracket` — tabela de liga não faz sentido em mata-mata; use o bracket abaixo.

Acesso: aplica o controle de visibilidade do torneio (owner, member ACTIVE, ou PUBLIC não-DRAFT); senão **404**.

### `BracketResponse`

```ts
export interface BracketResponse {
  phaseId: string;
  phaseName: string;
  rounds: BracketRound[];     // ordenadas da primeira rodada do mata-mata até a final
}

export interface BracketRound {
  round: number;              // ordinal sequencial (1 = primeira rodada)
  name: string;               // rótulo derivado: "Final", "Semifinals", "Quarterfinals", "Round of 16", ...
  ties: BracketTie[];
}

export interface BracketTie {
  tieId: string;
  homeTeam: TeamRef | null;
  awayTeam: TeamRef | null;
  homeAggregate: number;      // soma das pernas COMPLETED, orientada ao mandante da 1ª perna
  awayAggregate: number;
  homePenalties: number | null;   // só preenchido se o confronto foi a pênaltis
  awayPenalties: number | null;
  winner: TeamRef | null;     // null enquanto não houver vencedor (jogos pendentes ou empate sem pênaltis)
  complete: boolean;          // true quando todas as pernas estão resolvidas (sem SCHEDULED)
  thirdPlace: boolean;        // true se for a disputa de 3º lugar
  legs: MatchResponse[];      // 1 perna em SINGLE, 2 em TWO_LEGGED (ordenadas por round)
}
```

### `GET /api/tournaments/{tid}/phases/{pid}/bracket` → 200 `BracketResponse`

Exclusivo de phase `KNOCKOUT`. Monta a árvore de confrontos a partir dos matches da phase, agrupando as pernas por `tieId`, calculando o placar agregado e o vencedor de cada confronto. As rodadas são ordenadas da primeira (oitavas, por ex.) até a final.

- `winner` fica `null` enquanto o confronto não tiver desfecho: pernas pendentes, ou empate no agregado **sem pênaltis lançados**. Para desempatar, o owner lança os pênaltis no `setResult` (ver §14).
- Quando a phase tem `hasThirdPlace`, a última rodada contém dois confrontos: a final e a disputa de 3º lugar (`thirdPlace: true`). O flag vem do `matchType` persistido da partida — sempre confiável. A final aparece antes do 3º lugar.
- Phase sem matches retorna `rounds: []`.
- Em phase `ROUND_ROBIN`/`GROUPS` retorna **409** `Bracket is only available for KNOCKOUT phases; use /standings`.

Acesso: aplica o controle de visibilidade do torneio (owner, member ACTIVE, ou PUBLIC não-DRAFT); senão **404**. Mesmo padrão de `/standings` e `/ranking`.

### `POST /api/tournaments/{tid}/phases/{pid}/finalize` → 200 `StandingsResponse`

Owner-only. Processa as zones da phase e materializa os times classificados como `PhaseTeam` na `nextPhase` apontada por cada zone. Em caso de sucesso, marca `phase.finalizedAt = now` (visível no `PhaseResponse`).

**Pré-condições** (todos retornam **409**):
- Todos os matches resolvidos (`COMPLETED` ou `CANCELLED`, sem `SCHEDULED`): `Phase has N unfinished matches`.
- Pelo menos 1 match: `Phase has no matches to finalize`.
- `nextPhase` da zone não pode já ter times: `Next phase 'X' already has teams; cannot finalize` (idempotência).

**Processamento**:
- Zonas em ordem de `position`.
- `selectionMode = ALL`: pega times nas posições `[fromPosition, toPosition]` de cada grupo e cria `PhaseTeam` no `nextPhase`.
- `selectionMode = BEST_RANKED`: junta os times da posição `fromPosition` de **todos os grupos**, ranqueia (pontos → vitórias → saldo → gols pró → menos derrotas → nome) e leva os top `bestRankedCount`.
- Zone com `nextPhase = null` → times daquela faixa caem no vácuo.

---

## 17. Palpites (`/api/tournaments/{tid}/matches/{mid}/predictions` + variantes)

### `PredictionResponse`

```ts
export interface PredictionResponse {
  id: string;                 // UUID público
  matchId: string;
  userId: string;
  userName: string;
  homeScore: number | null;   // redigido (null) enquanto não puder ser revelado
  awayScore: number | null;   // idem
  homeExtraTimeScore: number | null;  // placar palpitado da prorrogação (KO jogo único); idem redação
  awayExtraTimeScore: number | null;
  penaltyWinner: 'HOME' | 'AWAY' | null;  // quem o user acha que passa nos pênaltis; idem redação
  points: number | null;      // idem; recomputado quando o resultado do match muda
  createdAt: string;
  updatedAt: string;
}
```

> Os campos `homeScore`, `awayScore`, `homeExtraTimeScore`, `awayExtraTimeScore`, `penaltyWinner` e `points` podem vir `null` no listing público (`GET .../predictions`) quando os palpites ainda não foram liberados — ver regra abaixo. Em `PUT /predictions/me` (palpite do próprio user) e `GET /predictions/me` (meus palpites) eles **sempre** vêm preenchidos. `homeExtraTimeScore`/`awayExtraTimeScore`/`penaltyWinner` também são `null` quando o palpite não envolve prorrogação/pênaltis (palpite não-empate, ou partida que não é de mata-mata elegível).

### `PUT /api/tournaments/{tid}/matches/{mid}/predictions/me` → 200

**Upsert**: se o usuário ainda não tem palpite no match, cria; senão atualiza.

```ts
export interface PlacePredictionRequest {
  homeScore: number;          // ≥ 0  (tempo normal)
  awayScore: number;          // ≥ 0
  homeExtraTimeScore?: number | null; // prorrogação, ≥ 0 e ≥ placar do 90' (só KO jogo único)
  awayExtraTimeScore?: number | null;
  penaltyWinner?: 'HOME' | 'AWAY';    // quem passa nos pênaltis (ver cascata abaixo)
}
```

#### Cascata de palpite em mata-mata de **jogo único** (`KNOCKOUT` + `matchLegMode = SINGLE`)

Detecte "jogo único de KO" pelo `phase.phaseType`/`phase.matchLegMode` (o feed pessoal §14.1 traz esses campos no `PhaseRef`). A cascata é validada no backend (**400** se violada), mas o front deve conduzir o formulário:

```
1. homeScore / awayScore (tempo normal).
2. Empate no 90'? (homeScore === awayScore)
   ├─ NÃO → sem prorrogação/pênaltis. Enviar extraTime/penaltyWinner = null/ausente.
   └─ SIM → prorrogação OBRIGATÓRIA. Placar mínimo de cada time = placar do 90' (cumulativo).
3. Prorrogação empatada? (homeExtraTimeScore === awayExtraTimeScore)
   ├─ NÃO → sem pênaltis. penaltyWinner = null/ausente.
   └─ SIM → penaltyWinner OBRIGATÓRIO (HOME | AWAY).
```

Erros (**400**):
- `extra-time score is required when you predict a draw in a single-leg knockout` — empatou o 90' e não mandou a prorrogação.
- `extra-time score cannot be lower than your regular-time score` — prorrogação menor que o 90'.
- `penaltyWinner is required when your extra-time prediction is a draw` — prorrogação empatada, sem `penaltyWinner`.
- `penaltyWinner only applies when your extra-time prediction is a draw` — mandou `penaltyWinner` com prorrogação decidida.
- `extraTimeScore only applies when you predict a draw in regular time` — mandou prorrogação sem empatar o 90'.
- `penaltyWinner only applies when your prediction ends level` — mandou `penaltyWinner` sem empatar o 90'.
- `extraTimeScore only applies to a single-leg knockout match` — mandou prorrogação fora de KO jogo único.

#### `penaltyWinner` em ida-e-volta (`TWO_LEGGED`)

Aqui **não há prorrogação**. `penaltyWinner` (lado relativo ao jogo palpitado) segue a regra do agregado:
- Só na **perna de volta** elegível (`penaltyShootoutEligible = true` no `MatchResponse`). Na ida, não se envia.
- Gatilho = empate no agregado = `aggregateBeforeHome + homeScore === aggregateBeforeAway + awayScore`.
- **Obrigatório** nesse empate: ausente → **400** `penaltyWinner is required when your prediction ends the tie in a draw`.
- Fora de confronto elegível → **400** `penaltyWinner only applies to a single-leg knockout match or the second leg of a two-legged tie`.
- Sem empate no agregado → **400** `penaltyWinner only applies when your prediction ends the tie in a draw`.

**Janela de palpite** (depende de a partida ter ou não horário):
- **Com `scheduledAt`**: pode palpitar até o horário marcado. Quando `now >= scheduledAt` → **409** `Predictions are locked for this match`.
- **Sem `scheduledAt`**: pode palpitar até o resultado real ser lançado (match vira `COMPLETED`). Depois → **409** `Predictions are locked: the match result has already been set`. (Não precisa mais marcar data para poder palpitar.)

Demais validações (todos 409 exceto onde indicado):
- `Predictions are only accepted while tournament is IN_PROGRESS` — torneio fora de IN_PROGRESS.
- `Match is cancelled`
- **403** `You are not an active member of this tournament`

### `DELETE /api/tournaments/{tid}/matches/{mid}/predictions/me` → 204

Remove meu palpite. Sujeito à mesma janela de palpite acima (bloqueado depois do `scheduledAt`, ou depois do resultado lançado quando não há data).

### `GET /api/tournaments/{tid}/matches/{mid}/predictions` → 200 `PredictionResponse[]`

Lista todos os palpites do match. **Nunca falha por janela de tempo** — owner e members ACTIVE chamam livremente a qualquer momento. A redação acontece no DTO.

**Acesso** (403 se faltar):
- Owner do torneio: ok.
- Member ACTIVE: ok.
- Demais usuários autenticados (incluindo members `LEFT`/`BANNED`): **403** `You are not an active member of this tournament`.

**Redação dos campos `homeScore`/`awayScore`/`points`** (aplicada uniformemente — owner não tem mais privilégio aqui):

| Estado do match | Scores e pontos retornados |
| --------------- | -------------------------- |
| `scheduledAt != null` e `now < scheduledAt` | `null` (palpite escondido) |
| `scheduledAt != null` e `now >= scheduledAt` | preenchidos |
| `scheduledAt == null` e `status != COMPLETED` | `null` (palpite escondido até resultado final) |
| `scheduledAt == null` e `status == COMPLETED` | preenchidos |

Campos `id`, `matchId`, `userId`, `userName`, `createdAt`, `updatedAt` vêm sempre — dá pra mostrar "X usuários já palpitaram" antes do jogo sem vazar conteúdo.

### `GET /api/tournaments/{tid}/matches/{mid}/predictions/stats` → 200 `PredictionStatsResponse`

Distribuição agregada dos palpites do match (mandante / empate / visitante), **sem expor palpites individuais** — serve pro card "Previsão da Galera" mesmo antes do jogo, quando os placares individuais ainda vêm redigidos no listing.

```ts
export interface PredictionStatsResponse {
  totalVotes: number;
  homeWin: number;      // palpites cujo desfecho é vitória do mandante
  draw: number;         // empate
  awayWin: number;      // vitória do visitante
  homeWinPct: number;   // 0–100, arredondado no servidor (os três somam 100)
  drawPct: number;
  awayWinPct: number;
}
```

> **Desfecho considera a prorrogação.** Para cada palpite, o desfecho (mandante/empate/visitante) usa o **placar da prorrogação** (`homeExtraTimeScore`/`awayExtraTimeScore`) quando o palpiteiro informou; senão o placar do tempo normal. Ex.: palpite 1×1 no tempo normal e 2×1 na prorrogação conta como **vitória do mandante**, não empate. Pênaltis **não** entram nessa distribuição — um palpite que vai a pênaltis (prorrogação empatada) conta como **empate** (a galera acha que "vai pra decisão").

- **Nunca falha por janela de tempo** — pode ser chamado a qualquer momento (antes ou depois do jogo).
- Percentuais arredondados no servidor pelo método do maior resto (somam exatamente 100). `totalVotes === 0` → todos os pct = 0.
- **Acesso**: owner ou member ACTIVE; senão **403** `You are not an active member of this tournament`. Match inexistente no torneio → **404**.

### `GET /api/tournaments/{tid}/predictions/me` → 200 `PredictionResponse[]`

Meus palpites no torneio inteiro. Acessível pra qualquer member ACTIVE (incluindo owner). Não redige nada — são os seus próprios palpites.

### `GET /api/tournaments/{tid}/predictions?userId={userId}` → 200 `PredictionResponse[]`

Palpites de **um participante específico** no torneio inteiro (todas as partidas em que palpitou). Use para a aba "Palpites" da tela de um participante.

- `userId` (query param, **obrigatório**) — `publicId` do participante.
- **Acesso**: owner ou member ACTIVE; senão **403** `You are not an active member of this tournament`.
- **Redação por partida** (igual ao listing por match): `homeScore`/`awayScore`/`points` só vêm preenchidos quando o palpite já pode ser revelado naquela partida (após `scheduledAt`, ou após o resultado quando não há data); senão `null`. Preserva a privacidade dos palpites futuros do participante.
- `userId` inexistente / sem palpites → `[]` (lista vazia).
- Ordenação não garantida — o front junta com os dados das partidas.

### `POST /api/tournaments/{tid}/predictions/recalculate` → 200 `RecalculationResponse`

Reaplica as **regras de pontuação vigentes** do torneio a **todos os palpites já existentes**, partida por partida. Sem body.

**Motivação.** Quando o owner altera a pontuação (`exactScorePoints` / `winnerPoints` / `wrongPoints`) com o torneio **em andamento**, a mudança só vale automaticamente para resultados lançados dali em diante (cada `setResult` recalcula apenas os palpites daquela partida). As partidas já lançadas continuam com os pontos calculados pela regra antiga. Este endpoint é a ação **explícita** para o owner recalcular o histórico inteiro com a nova pontuação. Se ele **não** chamar, a regra antiga permanece nos jogos já lançados e a nova vale só dos próximos em diante.

Comportamento:
- **Owner-only** — senão **403** `Only the tournament owner can perform this action`.
- Percorre todas as partidas do torneio. Para cada palpite, recomputa `points` com os `TournamentSettings` atuais (mesma lógica de `setResult`). Partidas não-`COMPLETED` (incluindo `CANCELLED`) zeram os pontos, como já acontece em `setResult`/`cancel`.
- **Idempotente**: rodar de novo sem ter mudado a pontuação/resultados não altera nenhum ponto (`predictionsUpdated: 0`).
- O **ranking** é calculado on-demand a partir de `prediction.points`, então reflete o novo cenário já na próxima leitura de `GET .../ranking` — não há passo extra.
- Disponível em qualquer status do torneio (em `FINISHED` é efetivamente um no-op, já que pontuação e resultados estão congelados). Torneio inexistente / soft-deletado → **404** `Tournament not found`.

```ts
export interface RecalculationResponse {
  totalMatches: number;        // total de partidas no torneio
  matchesProcessed: number;    // partidas que tinham ao menos um palpite e foram reavaliadas
  predictionsUpdated: number;  // palpites cujo `points` efetivamente mudou
}
```

Fluxo típico no front: owner abre as configurações de pontuação → salva a nova pontuação (`PUT /api/tournaments/{id}`) → aparece um aviso "a nova pontuação só vale para os próximos jogos; deseja recalcular os jogos já lançados?" → se confirmar, chama este endpoint.

---

## 18. Ranking

### `RankingRowResponse`

```ts
export interface RankingRowResponse {
  position: number;           // 1-indexed
  userId: string;
  name: string;
  avatarUrl: string;          // DiceBear, derivado do nome
  totalPoints: number;
  exactScoreHits: number;     // palpites com placar exato em matches COMPLETED
  winnerHits: number;         // acertou só o vencedor
  wrongs: number;             // errou completamente
  totalPredictions: number;   // total de palpites do user no torneio
}
```

### `GET /api/tournaments/{tid}/ranking` → 200 `RankingRowResponse[]`

Calculado on-demand. Ordenado por `totalPoints` desc → `exactScoreHits` desc → `winnerHits` desc → `wrongs` asc → nome asc.

Sem paginação por enquanto — array completo. Acesso: aplica o controle de visibilidade do torneio (owner, member ACTIVE, ou PUBLIC não-DRAFT); torneio `PRIVATE` não vaza ranking para quem não participa (**404**).

#### Filtros (query params, todos opcionais e combináveis)

| Param | Tipo | Descrição |
| ----- | ---- | --------- |
| `phaseId` | UUID | Considera só palpites de partidas dessa fase. **404** `Phase not found` se a fase não pertence ao torneio. |
| `groupId` | UUID | Só partidas desse grupo. **Exige `phaseId`** (grupo pertence a uma fase): sem ele → **400** `groupId requires phaseId (a group belongs to a phase)`. **404** `Group not found` se o grupo não pertence à fase. |
| `round` | int | Só partidas dessa rodada. (O número de rodada se repete entre fases — combine com `phaseId` para isolar uma rodada específica.) |
| `matchType` | enum `REGULAR` \| `THIRD_PLACE` | Só partidas desse tipo. Separa a **Final** (`REGULAR`) da **Disputa de 3º** (`THIRD_PLACE`), que compartilham o mesmo `round` em mata-mata. Valor inválido → **400** `Invalid value for parameter 'matchType'`. |
| `memberStatus` | enum `ACTIVE` \| `LEFT` \| `BANNED` | Restringe o ranking aos membros cuja participação no torneio está naquele status. **Omitido = todos** que palpitaram, independente do status atual (comportamento anterior, inalterado). `ACTIVE` esconde quem saiu (`LEFT`) ou foi banido (`BANNED`); `LEFT`/`BANNED` mostram só esses. Valor inválido → **400** `Invalid value for parameter 'memberStatus'`. |

Sem nenhum filtro, agrega o torneio inteiro (comportamento anterior, inalterado). Com filtros, **todos os campos** do `RankingRowResponse` (`totalPoints`, `exactScoreHits`, `winnerHits`, `wrongs`, `totalPredictions`) passam a refletir apenas o recorte filtrado, e `position` é recalculado dentro do recorte. Filtros válidos sem partidas correspondentes retornam `[]`.

#### Pick'em no ranking

`totalPoints` inclui os pontos de **Pick'em de fase** (§20) somados aos pontos de palpite de partida — **sem breakdown** aqui (o detalhamento fica no perfil do palpiteiro, endpoint `summary`, em breve). Regras do recorte:

- **Sem filtro**: soma os Pick'ems de todas as fases.
- **Só `phaseId`**: soma apenas o Pick'em daquela fase.
- **`groupId`/`round`/`matchType` presentes**: Pick'em **fica de fora** (ele é da fase inteira — não é recortável por grupo/rodada/partida); o recorte vira só de palpites de partida.
- `memberStatus` também filtra os pontos de Pick'em (mesma semântica).

Usuário que só tem Pick'em (nenhum palpite de partida) **aparece no ranking** com `totalPredictions = 0` e os contadores de acerto zerados. Os contadores (`exactScoreHits`/`winnerHits`/`wrongs`) seguem sendo **apenas de palpites de partida**.

Exemplos:
- `GET .../ranking?phaseId={fase}` — ranking só da fase de grupos.
- `GET .../ranking?phaseId={fase}&groupId={grupo}` — ranking só do Grupo A.
- `GET .../ranking?phaseId={fase}&round=3` — ranking só da 3ª rodada daquela fase.
- `GET .../ranking?phaseId={mata-mata}&round={final}&matchType=REGULAR` — só a Final.
- `GET .../ranking?phaseId={mata-mata}&round={final}&matchType=THIRD_PLACE` — só a Disputa de 3º.
- `GET .../ranking?memberStatus=ACTIVE` — ranking só dos membros ativos (esconde quem saiu/foi banido).

---

## 18.1 Retrospecto / análise da partida

Read model **agregado** que alimenta a aba **"Retrospecto"** no detalhe da partida: contexto na competição, confronto direto, forma recente e desempenho dos palpiteiros. Um único objeto (sem paginação) — pensado para carregamento **sob demanda** (o front chama só quando abre a aba). Todos os números são calculados no backend; o front só apresenta.

### `MatchAnalysisResponse`

```ts
export interface MatchAnalysisResponse {
  matchId: string;
  phaseType: 'ROUND_ROBIN' | 'KNOCKOUT' | 'GROUPS';   // tipo da fase DESTA partida
  home: TeamFormSummary;      // mandante DESTA partida
  away: TeamFormSummary;      // visitante DESTA partida
  headToHead: HeadToHead;
  expectedGoals: ExpectedGoals | null;  // projeção pela forma; null se algum time sem jogos
  recentWindow: number;       // janela de "últimos jogos" (10)
  headToHeadWindow: number;   // janela de confrontos diretos (2)
}

export interface ExpectedGoals {   // projeção pela forma (ataque × defesa), NÃO é xG de finalização
  home: number;               // gols esperados do mandante (1 casa decimal)
  away: number;
}

export interface TeamFormSummary {
  team: TeamRef;                        // mesmo TeamRef do MatchResponse (cores/countryCode)
  standing: TeamStandingContext | null; // null quando o contexto seria NONE
  recentMatches: TeamFormMatch[];       // mais recente → mais antigo, até recentWindow
  stats: TeamFormStats;
  predictors: TeamPredictorStats;       // desempenho dos palpiteiros com este time
  restDays: number | null;              // dias do último jogo até esta partida; null se indeterminado
}

export interface TeamStandingContext {
  kind: 'ROUND_ROBIN' | 'GROUP' | 'PREVIOUS_PHASE';
  position: number;         // 1-indexed
  totalTeams: number;       // total na tabela/grupo referenciado (para "3º de 8")
  points: number | null;
  played: number | null;
  groupName: string | null; // preenchido só quando kind === 'GROUP' (ou PREVIOUS_PHASE vindo de grupos)
  phaseId: string;          // fase a que a posição se refere (a atual, ou a anterior no KO)
  phaseName: string;
}

export interface TeamFormMatch {
  matchId: string;
  phaseId: string;
  phaseName: string;
  round: number;
  scheduledAt: string | null;
  opponent: TeamRef;
  playedHome: boolean;          // o dono do card jogou como mandante?
  goalsFor: number;             // placar DECISIVO (prorrogação se houve, senão 90')
  goalsAgainst: number;
  outcome: 'W' | 'D' | 'L';     // pela ótica do dono, pelo placar decisivo
  hadExtraTime: boolean;
  penaltyFor: number | null;    // pênaltis do dono SE decidido nos pênaltis; senão null
  penaltyAgainst: number | null;
  advanced: boolean | null;     // avançou nos pênaltis? null quando não houve pênaltis
}

export interface TeamFormStats {
  played: number;               // jogos na janela (pode ser < recentWindow)
  wins: number; draws: number; losses: number;
  goalsFor: number; goalsAgainst: number; goalDifference: number;
  cleanSheets: number;          // jogos sem sofrer gol
  failedToScore: number;        // jogos sem marcar
  overTwoFive: number;          // jogos com 3+ gols no total
  bothTeamsScored: number;      // jogos em que os dois marcaram (BTTS)
  points: number;               // aproveitamento 3/1/0 (NÃO usa a pontuação do torneio)
  performancePct: number | null;// round(points / (played*3) * 100); null se played=0
  streak: FormStreak | null;    // sequência atual; null se played=0
  homeRecord: VenueRecord | null; // recorte como mandante; null se não jogou em casa na janela
  awayRecord: VenueRecord | null; // idem como visitante
}

export interface TeamPredictorStats {
  ratedMatches: number;             // jogos COMPLETED do time no torneio com ≥1 pitaco
  totalPredictions: number;
  exactScoreRate: number | null;    // % de pitacos com placar exato (0-100); null se 0 pitacos
  correctOutcomeRate: number | null;// % de pitacos que acertaram o desfecho; null se 0 pitacos
  averagePoints: number | null;     // média de pontos por pitaco; null se 0 pitacos
  upsetRate: number | null;         // % de jogos em que a maioria errou o desfecho; null se 0 jogos
}

export interface FormStreak {
  type: 'WIN' | 'LOSS' | 'DRAW' | 'UNBEATEN' | 'WINLESS';
  count: number;                    // >= 1
}

export interface VenueRecord {
  played: number; wins: number; draws: number; losses: number;
  goalsFor: number; goalsAgainst: number;
}

export interface HeadToHead {
  totalMeetings: number;        // confrontos anteriores entre os dois NO torneio (0 se nunca)
  homeTeamWins: number;         // orientado ao mandante DESTA partida...
  draws: number;
  awayTeamWins: number;         // ...independente de quem foi mandante em cada confronto
  homeTeamGoals: number;
  awayTeamGoals: number;
  averageGoals: number | null;  // (homeTeamGoals + awayTeamGoals) / totalMeetings; null se 0
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

> `TeamRef` é exatamente o de `MatchResponse` (§14) — com `primaryColor`/`secondaryColor`/`teamType`/`countryCode`.

### `GET /api/tournaments/{tournamentId}/matches/{matchId}/analysis` → 200 `MatchAnalysisResponse`

- Path **sem `phaseId`** (a partida resolve a fase internamente), igual às rotas de palpite (§17).
- Acesso: mesmo controle de visibilidade das demais rotas do torneio (owner, member `ACTIVE`, ou `PUBLIC` não-`DRAFT`); senão **404** `Tournament not found`. Não expõe pitacos individuais de ninguém.
- **404** `Match not found` se a partida não existe **ou** não pertence ao torneio.
- Sem paginação — um objeto agregado.

### Regras de cálculo

- **Escopo**: tudo (forma, confronto direto, palpiteiros) considera **somente partidas deste torneio**, atravessando todas as fases, apenas `COMPLETED`, **exceto a própria partida analisada**. Janelas fixas: `recentWindow = 10`, `headToHeadWindow = 2` (ecoadas no response).
- **Placar decisivo**: prorrogação se lançada (cumulativa, já inclui os 90'), senão o tempo normal. Pênaltis **não** entram nos gols — vão em `penaltyFor`/`penaltyAgainst` e `penaltyHomeGoals`/`penaltyAwayGoals`. Empate que foi a pênaltis conta como empate (`D`); quem passou vem em `advanced`.
- **Ordenação de "últimos jogos"**: `COALESCE(scheduledAt, createdAt)` desc, depois `createdAt`/`round` desc. Ida-e-volta aparece como **dois jogos** na forma (não agregados).
- **`standing`** (contexto posicional): `ROUND_ROBIN` → posição na tabela da fase atual; `GROUPS` → posição dentro do grupo na fase atual (`groupName` preenchido); `KNOCKOUT` vindo de liga/grupos → posição **final** na fase anterior (`kind = PREVIOUS_PHASE`, `phaseId`/`phaseName` = fase anterior); mata-mata vindo de mata-mata (ou sem fase anterior) → `standing: null`. Usa a mesma tabela/ordenação do `/standings` (§16).
- **`stats`**: aproveitamento **3/1/0 fixo** (não usa `winPoints`/`drawPoints` do torneio). `streak` do jogo mais recente para trás — pura → `WIN`/`LOSS`/`DRAW`; mista sem derrota → `UNBEATEN`; mista sem vitória → `WINLESS`. `homeRecord.played + awayRecord.played === stats.played`.
- **`predictors`** ("como a galera se sai"): sobre **todos** os jogos `COMPLETED` do time no torneio (não só a janela) com ≥1 pitaco. `exactScoreRate` = placar exato do **tempo normal**; `correctOutcomeRate` = acerto de desfecho (ambos os lados pelo placar decisivo/prorrogação); `averagePoints` usa `points` já gravado; `upsetRate` = % de jogos em que a **maioria** errou o desfecho. Taxas em escala **0–100**; `null` quando não há base.
- **`restDays`**: dias inteiros do jogo mais recente **com horário** (na janela) até o `scheduledAt` desta partida (ou "agora" se a partida não tem horário). Negativo/sem base → `null`.
- **`expectedGoals`**: `home = (homeGF + awayGA)/2`, `away = (awayGF + homeGA)/2` (médias da janela), 1 casa decimal. `null` se algum time tem `played = 0`. **Não** é xG de finalização — é projeção pela forma.

Percentuais em escala **0–100** e médias como decimal — o front formata (arredonda taxas para inteiro, gols para 1 casa).

---

## 19. Sistema de pontuação dos palpites

Todos os valores vêm do `TournamentSettings`. A pontuação de um palpite é a **soma** de até três componentes independentes (tempo normal + prorrogação + pênaltis). Match `CANCELLED` zera os pontos dos palpites associados; match `SCHEDULED` mantém `points = 0` (ainda não avaliado).

### Componente 1 — Tempo normal (sempre conta)

Compara o palpite do 90' (`homeScore`/`awayScore`) com o placar real do 90':

- **Placar exato** (`exactScorePoints`, default 5): `predictionHome === actualHome && predictionAway === actualAway`.
- **Vencedor / empate** (`winnerPoints`, default 2): errou o placar mas acertou o desfecho. Comparação via `Math.sign(home - away)`.
- **Erro** (`wrongPoints`, default 0): desfecho diferente.

### Componente 2 — Prorrogação (só quando a partida foi de fato à prorrogação)

Só em mata-mata de jogo único que teve prorrogação (o `MatchResponse` traz `homeExtraTimeScore`/`awayExtraTimeScore` preenchidos) **e** o palpiteiro informou o placar da prorrogação. Compara o palpite de ET com o placar real de ET:

- **Placar exato da prorrogação** (`extraTimeExactScorePoints`, default 2).
- **Só o vencedor da prorrogação** (`extraTimeWinnerPoints`, default 1): errou o placar de ET, acertou o desfecho.
- Errou o desfecho da prorrogação → `0` (não há "erro" configurável aqui).

### Componente 3 — Pênaltis (só quando o confronto foi decidido nos pênaltis)

Empate no placar decisivo (prorrogação se houve, senão 90' em jogo único; agregado em ida-e-volta) resolvido nos pênaltis, e o palpiteiro informou `penaltyWinner`:

- **Acertou quem passa** (`penaltyWinnerPoints`, default 2).
- Errou → `0`.

O "quem passou" real é o time com mais pênaltis (jogo único) ou o vencedor do agregado (ida-e-volta, avaliado via `TieAggregateCalculator`).

### Os três somam

Exemplo (KO jogo único que foi a pênaltis): palpite `2x2` no 90' + `3x3` na prorrogação + mandante passa nos pênaltis, e o resultado real bateu tudo:

```
exactScorePoints (90' exato 2x2)  +  extraTimeExactScorePoints (ET exato 3x3)  +  penaltyWinnerPoints (quem passou)
```

Com os defaults de exemplo `2 / 2 / 2` → **6 pontos**.

> ⚠️ **Mudou em relação ao comportamento anterior:** os pênaltis eram *combinados* no mesmo balde do tempo normal (2 acertos → exato, 1 → vencedor). Agora são um componente **aditivo** com pontuação própria (`penaltyWinnerPoints`), somado aos demais. Pontos de partidas já lançadas só refletem o novo modelo após um novo `setResult` ou via `POST /predictions/recalculate` (§17).

Detalhes:
- Prorrogação e pênaltis só contam quando o confronto **efetivamente** passou por eles. Decidido no 90' → só o componente 1. Foi à prorrogação → componentes 1 + 2 (+ 3 se a ET empatou e foi a pênaltis).
- Em ida-e-volta **não há prorrogação**; o `penaltyWinner` vai no palpite da **volta** e é avaliado contra o vencedor do agregado. Cada perna pontua o componente 1 pelo placar dela.

---

## 20. Pick'em de fase (`/api/tournaments/{tid}/phases/{pid}/pickem`)

> Feature completa. O plano (modelo, regras, decisões de produto) está em `PLANO_PICKEM.md`.

Palpite de **alto nível sobre a fase inteira**, feito **antes de a fase começar** (diferente do palpite de partida, que é jogo a jogo). O formato depende do `phaseType`:

- **`ROUND_ROBIN` / `GROUPS`** — o usuário monta a classificação prevista de cada tabela/grupo: quais times ficam na zona de classificação e em quais posições (incl. o 1º).
- **`KNOCKOUT`** — o usuário preenche quem passa em cada confronto, montando o caminho até a final (campeão, vice e 3º quando houver disputa).

Regras principais (já definidas):

- **Trava**: no início da 1ª partida da fase (`min(scheduledAt)`); se a fase não tem horários, trava quando o 1º resultado é lançado. Depois de travado, `PUT`/`DELETE` → 409.
- **Um Pick'em por usuário/fase** (upsert, como o palpite de partida).
- **Palpite parcial permitido** — slots não preenchidos simplesmente não pontuam.
- **Sempre visível**: qualquer member ACTIVE vê os Pick'ems dos outros a qualquer momento (não há redação).
- **Pontos somam no ranking geral** (§18) junto com os palpites de partida; a pontuação é **provisória** durante a fase (recalculada a cada resultado) e definitiva no fim.
- A pontuação de cada componente é configurável pelo admin nos campos `pickem*` de `TournamentSettingsPayload`/`TournamentSettingsResponse` (§7), todos com default `1`:

| Campo | Quando pontua |
| ----- | ------------- |
| `pickemQualifierPoints` | Time previsto na zona de classificação terminou classificado (independe da posição) |
| `pickemExactPositionPoints` | Time cravado na posição final exata da tabela/grupo |
| `pickemFirstPlacePoints` | Acertou o 1º da tabela/grupo |
| `pickemKoMatchupExactPoints` | Cravou os dois times de um confronto do mata-mata |
| `pickemKoMatchupPartialPoints` | Acertou pelo menos 1 time do confronto (**exclusivo** com o exato: paga um ou outro, nunca os dois) |
| `pickemChampionPoints` | Acertou o campeão |
| `pickemRunnerUpPoints` | Acertou o vice |
| `pickemThirdPlacePoints` | Acertou o 3º lugar (só quando a fase tem disputa de 3º) |

Componentes de tabela **somam** entre si (classificado + posição exata + 1º podem acumular no mesmo time). No mata-mata, confronto exato/parcial é exclusivo por confronto, mas soma com campeão/vice/3º.

| Método | Path | Status | Descrição |
| --- | --- | --- | --- |
| GET | `.../pickem/template` | 200 | Molde + estado da janela (§20.1) |
| PUT | `.../pickem/me` | 200 | Upsert do meu Pick'em (§20.2) |
| GET | `.../pickem/me` | 200 | Meu Pick'em (§20.3) |
| DELETE | `.../pickem/me` | 204 | Remove meu Pick'em (até travar) |
| GET | `.../pickem` | 200 | Listagem paginada — sempre visível (§20.4) |
| GET | `.../pickem/{userId}` | 200 | Pick'em de um participante (§20.5) |
| GET | `.../pickem/stats` | 200 | Previsão da galera (§20.6) |
| POST | `.../pickem/recalculate` | 200 | Owner: repontua a fase (§20.7) |
| GET | `/api/tournaments/{tid}/participants/{userId}/summary` | 200 | Perfil do palpiteiro (§20.8) |
| GET | `/api/users/me/pickems/pending` | 200 | Pendências do usuário — card "Palpitão aberto" (§20.9) |

### 20.1 `GET .../pickem/template` → 200

O "molde" que o front usa pra montar a tela do Pick'em: estado da janela, pontuação vigente e a estrutura a preencher. Acesso: mesma visibilidade do torneio (owner / member ACTIVE / PUBLIC não-DRAFT; senão **404**).

```ts
export type PickemState = 'NOT_READY' | 'OPEN' | 'LOCKED';

export interface PhasePredictionTemplateResponse {
  phaseId: string;
  phaseName: string;
  phaseType: TournamentPhaseType;      // 'ROUND_ROBIN' | 'GROUPS' | 'KNOCKOUT'
  state: PickemState;
  stateReason: string | null;          // só em NOT_READY (ver tabela abaixo)
  lockAt: string | null;               // min(scheduledAt) das partidas da fase; null = trava pelo 1º resultado
  scoring: PickemScoring;              // eco dos campos pickem* do TournamentSettings
  table: TableTemplate | null;         // preenchido em ROUND_ROBIN/GROUPS (quando pronto)
  bracket: BracketTemplate | null;     // preenchido em KNOCKOUT (quando pronto)
}

export interface PickemScoring {
  qualifierPoints: number;
  exactPositionPoints: number;
  firstPlacePoints: number;
  koMatchupExactPoints: number;
  koMatchupPartialPoints: number;
  championPoints: number;
  runnerUpPoints: number;
  thirdPlacePoints: number;
}

export interface TableTemplate {
  qualifyingDepth: number;             // profundidade global da zona de classificação
  groups: GroupBlock[];                // RR: 1 bloco com groupId/groupName null
}

export interface GroupBlock {
  groupId: string | null;
  groupName: string | null;
  qualifyingDepth: number;             // min(global, nº de times do bloco) — slots a renderizar
  teams: TeamRef[];                    // times disponíveis (ordem alfabética)
}

export interface BracketTemplate {
  hasThirdPlace: boolean;
  totalRounds: number;
  rounds: TemplateRound[];             // rodada 1 com os confrontos reais; seguintes com slots vazios
}

export interface TemplateRound {
  roundNumber: number;                 // 1-based (ordinal do bracket; ida+volta = 1 rodada)
  name: string;                        // "Final", "Semifinals", "Quarterfinals", ...
  slots: TemplateSlot[];
}

export interface TemplateSlot {
  slotIndex: number;                   // 0-based dentro da rodada
  homeTeam: TeamRef | null;            // null nas rodadas > 1 (o front preenche com os vencedores escolhidos)
  awayTeam: TeamRef | null;
}
```

**Estados** (`state`):

| `state` | Significado |
| --- | --- |
| `OPEN` | Janela aberta — aceita `PUT`/`DELETE /me`. |
| `LOCKED` | A fase começou (1ª partida iniciou, ou 1º resultado saiu quando não há horários). Palpites congelados. |
| `NOT_READY` | Sem substrato ou torneio fora de IN_PROGRESS — `stateReason` explica. |

**`stateReason`** (só em `NOT_READY`): `TOURNAMENT_NOT_IN_PROGRESS`, `NO_TEAMS`, `NO_GROUPS`, `TEAMS_NOT_ASSIGNED_TO_GROUPS`, `NO_QUALIFICATION_ZONES` (fase sem zona com `nextPhase` — sem faixa de classificação não há o que prever), `BRACKET_NOT_GENERATED` (KO sem a 1ª rodada gerada).

**Como montar a árvore do KO no front:** a rodada 1 vem fixa (pares reais). O vencedor escolhido no slot `2j` alimenta o `homeTeam` e o do slot `2j+1` o `awayTeam` do slot `j` da rodada seguinte. Se `hasThirdPlace`, renderize um slot extra `THIRD_PLACE` na rodada final (slotIndex 0) com os perdedores previstos das semifinais.

### 20.2 `PUT .../pickem/me` → 200 (upsert)

Cria ou substitui **por inteiro** o Pick'em do usuário na fase. Envie `positions` em RR/GROUPS **ou** `ties` em KNOCKOUT (o outro campo omitido/vazio). Palpite **parcial é permitido** — slots omitidos não pontuam.

```ts
export interface PlacePhasePredictionRequest {
  positions?: PositionPick[];          // RR/GROUPS
  ties?: TiePick[];                    // KNOCKOUT
}

export interface PositionPick {
  groupId?: string | null;             // obrigatório em GROUPS; proibido em RR
  teamId: string;
  position: number;                    // 1..qualifyingDepth do bloco
}

export interface TiePick {
  roundNumber: number;                 // 1-based
  slotIndex: number;                   // 0-based
  matchType?: 'REGULAR' | 'THIRD_PLACE'; // default REGULAR
  homeTeamId: string;
  awayTeamId: string;
  winnerTeamId: string;                // um dos dois acima
}
```

Regras validadas no backend (violação → **400**, salvo indicação):

- Tipo × payload: `positions` só em RR/GROUPS, `ties` só em KNOCKOUT.
- **Tabela**: time pertence ao bloco; `position <= qualifyingDepth`; sem posição nem time repetido no mesmo bloco.
- **Bracket**: todos os times pertencem ao bracket; `winnerTeamId` ∈ par; time não se repete na mesma rodada; **rodada 1 é fixa** (o par do slot deve bater com o confronto real); slots de rodadas seguintes devem ser formados pelos **vencedores que o próprio usuário escolheu** (checado quando o slot-filho foi enviado); `THIRD_PLACE` só se a fase tem 3º lugar, na rodada final, com os perdedores previstos das semifinais (e sem repetir os times da final).
- Janela: torneio fora de IN_PROGRESS, fase travada ou não-pronta → **409** (mensagens abaixo).

### 20.3 `GET .../pickem/me` → 200 | `DELETE .../pickem/me` → 204

`GET /me` retorna o Pick'em do usuário autenticado (404 `Phase prediction not found` se não palpitou). `DELETE /me` remove (só com a janela aberta; travada → 409).

```ts
export interface PhasePredictionResponse {
  id: string;
  phaseId: string;
  userId: string;
  userName: string;
  avatarUrl: string;
  phaseType: TournamentPhaseType;
  points: number;                      // total do Pick'em (provisório até a fase finalizar)
  provisional: boolean;                // true enquanto a fase não foi finalizada
  scoredAt: string | null;             // última repontuação; null = ainda não pontuado
  positions: PositionRow[];            // vazio em KNOCKOUT
  ties: TieRow[];                      // vazio em RR/GROUPS
  terminals: TerminalOutcome | null;   // só KNOCKOUT (campeão/vice/3º); null sem base
  createdAt: string;
  updatedAt: string;
}

export interface PositionRow {
  groupId: string | null;
  groupName: string | null;
  team: TeamRef;
  predictedPosition: number;
  outcome: PositionOutcome | null;     // null enquanto não há resultados na fase
}

export interface PositionOutcome {
  qualifiedHit: boolean | null;
  exactPositionHit: boolean | null;
  firstPlaceHit: boolean | null;
  pointsAwarded: number | null;        // soma dos componentes deste slot
}

export interface TieRow {
  roundNumber: number;
  slotIndex: number;
  matchType: 'REGULAR' | 'THIRD_PLACE';
  homeTeam: TeamRef;
  awayTeam: TeamRef;
  winnerTeam: TeamRef;
  outcome: TieOutcome | null;          // null enquanto o confronto real não existe
}

export interface TieOutcome {
  matchup: 'EXACT' | 'PARTIAL' | 'MISS' | null; // null na rodada 1 (pares dados)
  winnerAdvanced: boolean | null;
  pointsAwarded: number | null;
}

export interface TerminalOutcome {
  championHit: boolean | null;
  runnerUpHit: boolean | null;
  thirdPlaceHit: boolean | null;
  pointsAwarded: number | null;
}
```

> **Pontuação ativa.** `points` é recalculado automaticamente a cada resultado lançado/editado/cancelado e no finalize da fase (provisório → definitivo). Os campos `outcome`/`terminals` são calculados on-demand nas leituras: vêm `null` enquanto a fase não tem nenhum resultado; depois refletem o estado real atual. No mata-mata, o `matchup` da **rodada 1 é sempre `null`** (os pares são dados — não pontuam confronto); o matching das rodadas seguintes não depende da ordem dos slots (o backend maximiza os acertos parciais do palpiteiro).

### 20.4 `GET .../pickem` → 200 (listagem)

Todos os Pick'ems da fase, paginados (`Page<PhasePredictionResponse>`, formato padrão §4). **Sempre visíveis** — sem redação, mesmo antes da trava. Ordem fixa: `points` desc, nome asc (não aceita `sort`). Acesso: visibilidade do torneio (owner / member ACTIVE / PUBLIC não-DRAFT).

### 20.5 `GET .../pickem/{userId}` → 200

Pick'em de um participante específico (`PhasePredictionResponse`). **404** `Phase prediction not found` se ele não palpitou nesta fase.

### 20.6 `GET .../pickem/stats` → 200

"Previsão da galera" da fase — só contagens/percentuais, nunca palpites individuais. Pode ser exibido a qualquer momento.

```ts
export interface PhasePredictionStatsResponse {
  phaseId: string;
  phaseType: TournamentPhaseType;
  totalPickems: number;                // nº de Pick'ems registrados na fase
  table: TableStats | null;            // RR/GROUPS
  bracket: BracketStats | null;        // KNOCKOUT
}

export interface TableStats {
  groups: GroupStats[];                // RR: 1 bloco com groupId/groupName null
}

export interface GroupStats {
  groupId: string | null;
  groupName: string | null;
  pickems: number;                     // Pick'ems com ≥1 palpite neste bloco (base do pct de qualifiers)
  firstPlace: TeamShare[];             // distribuição de "quem fica em 1º" — pcts somam 100
  qualifiers: TeamShare[];             // % dos Pick'ems que colocaram o time na zona — NÃO soma 100
}

export interface BracketStats {
  champion: TeamShare[];               // distribuição de campeão previsto — pcts somam 100
  runnerUp: TeamShare[];               // idem vice
  thirdPlace: TeamShare[];             // idem 3º (vazio se a fase não tem 3º lugar)
}

export interface TeamShare {
  team: TeamRef;
  count: number;
  pct: number;                         // 0–100 inteiro
}
```

> Listas ordenadas por `count` desc. Nas distribuições de escolha única (`firstPlace`, `champion`, `runnerUp`, `thirdPlace`) os `pct` somam exatamente 100 (método do maior resto); em `qualifiers` cada `pct` é individual (um Pick'em escolhe vários times, a soma passa de 100).

### 20.7 `POST .../pickem/recalculate` → 200 (owner)

Repontua **todos** os Pick'ems da fase contra o estado real atual. Rede de segurança para quando o owner altera a pontuação `pickem*` com a fase já rolando (por padrão a mudança só vale do próximo resultado em diante). Idempotente. Não-owner → **403**.

```ts
export interface PickemRecalculationResponse {
  pickemsRecalculated: number;
}
```

### 20.8 Perfil do palpiteiro — `GET /api/tournaments/{tid}/participants/{userId}/summary` → 200

Desempenho **explicado e separado** de um participante: quanto veio de palpites de partida, quanto veio de Pick'em (por fase e por componente). O ranking (§18) mostra só o total; o detalhamento mora aqui. Acesso: visibilidade do torneio. **404** `Participant not found in this tournament` se o usuário nunca foi membro.

```ts
export interface ParticipantSummaryResponse {
  userId: string;
  userName: string;
  avatarUrl: string;
  rankingPosition: number | null;     // null se não aparece no ranking (nenhum palpite/pick'em)
  totalPoints: number;                // == ranking (partidas + pick'em)
  matchPoints: number;                // só palpites de partida
  pickemPoints: number;               // só Pick'em de fase
  matchBreakdown: {
    totalPredictions: number;
    exactScoreHits: number;
    winnerHits: number;
    wrongs: number;
  };
  pickemByPhase: PickemPhaseBreakdown[];  // ordenado pela posição da fase
}

export interface PickemPhaseBreakdown {
  phaseId: string;
  phaseName: string;
  phaseType: TournamentPhaseType;
  provisional: boolean;               // true enquanto a fase não foi finalizada
  points: number;                     // total do Pick'em nesta fase
  components: {                       // decomposição (soma = points)
    qualifier: number;                // times classificados acertados
    exactPosition: number;            // posições exatas cravadas
    firstPlace: number;               // 1º da tabela/grupo
    koMatchupExact: number;           // confrontos cravados (A+B)
    koMatchupPartial: number;         // confrontos com 1 time certo
    champion: number;
    runnerUp: number;
    thirdPlace: number;
  };
}
```

### 20.9 Pendências do usuário — `GET /api/users/me/pickems/pending` → 200

Fases em que o usuário autenticado **ainda pode e ainda não fez** o Pick'em — alimenta o card "Palpitão aberto" da home (mesmo espírito do `pending-count` de partidas, §14.1). Escopado ao próprio usuário; basta estar autenticado. Sem query params, sem paginação — array simples (universo naturalmente curto). Sem pendências → `[]`.

```ts
export interface PendingPickemResponse {
  tournamentId: string;
  tournamentName: string;
  phaseId: string;
  phaseName: string;
  phaseType: TournamentPhaseType;      // 'ROUND_ROBIN' | 'GROUPS' | 'KNOCKOUT'
  lockAt: string | null;               // min(scheduledAt) das partidas da fase (ISO);
                                       // null = trava quando o 1º resultado for lançado
}
```

Uma fase entra na lista quando **todas** as condições valem:

1. Torneio **ativo** e **`IN_PROGRESS`**, com o usuário como member **`ACTIVE`** (inclui o owner, que é member auto).
2. O Pick'em da fase está **`OPEN`** — exatamente a mesma regra do `template` (§20.1), mesma fonte de verdade no backend: substrato pronto **e** ainda não travou. Fases `NOT_READY` (ex.: KO sem a 1ª rodada gerada) e `LOCKED` ficam de fora.
3. O usuário **ainda não tem** Pick'em naquela fase (quem já palpitou sai da lista).

Ordenação por urgência: `lockAt` asc com **nulls por último**; empate → `tournamentName` asc (case-insensitive), depois posição da fase.

### 20.10 Erros do Pick'em

| Status | `message` | Quando |
| --- | --- | --- |
| 409 | `Phase pick'em is locked (the phase has started)` | `PUT`/`DELETE /me` após a trava |
| 409 | `Phase pick'em is only accepted while tournament is IN_PROGRESS` | Torneio em DRAFT/OPEN/FINISHED |
| 409 | `Phase pick'em is not available yet: <reason>` | Fase sem substrato (`stateReason` da §20.1) |
| 403 | `You are not an active member of this tournament` | Requester não é member ACTIVE |
| 403 | `Only the tournament owner can perform this action` | `POST /recalculate` por não-owner |
| 404 | `Phase prediction not found` | `GET`/`DELETE /me` ou `GET /{userId}` sem palpite |
| 404 | `Participant not found in this tournament` | `summary` de quem nunca foi membro |
| 404 | `Tournament not found` / `Phase not found` | ids inválidos ou sem acesso |
| 400 | mensagens da §20.2 | payload inválido |

---

## 21. Fluxos típicos pro frontend

### Cadastro e login

1. `POST /api/auth/signup` ou `/signin` → guarda tokens.
2. Interceptor de request injeta `Authorization: Bearer <accessToken>`.
3. Interceptor de resposta: 401 → tenta `/refresh`; se falhar → logout.

### Criar e configurar torneio

1. `POST /api/tournaments` (status nasce DRAFT).
2. Vincula times próprios: `POST /api/tournaments/{id}/teams/{teamId}` para cada um.
3. Cria phases: `POST /api/tournaments/{id}/phases` na ordem desejada. A 1ª (position 0) já recebe os times automaticamente.
4. Em phase GROUPS: cria grupos via `POST /api/tournaments/{tid}/phases/{pid}/groups`, depois distribui times (manualmente via `PUT teams/{teamId}` com `groupId`, ou via `POST teams/draw`).
5. Cria zones (`POST /api/tournaments/{tid}/phases/{pid}/zones`) configurando avanço entre phases.
6. Avança status: `POST /api/tournaments/{id}/status` com `{ targetStatus: "OPEN" }`.
7. Compartilha `inviteCode` com participantes — `POST /api/tournaments/join`.
8. Quando estiver pronto pra começar: `POST /api/tournaments/{id}/status` com `{ targetStatus: "IN_PROGRESS" }`.

### Geração de partidas

Em phase com `matchGenerationMode = AUTOMATIC`, chama `POST /api/tournaments/{tid}/phases/{pid}/matches/generate` (geralmente quando torneio está em OPEN ou IN_PROGRESS).

Em `MANUAL`, o admin cria cada partida via `POST .../matches`.

### Fluxo de palpite (usuário comum)

1. Usuário acessa torneio onde é member ACTIVE.
2. Lista matches: `GET /api/tournaments/{tid}/phases/{pid}/matches` (vai precisar iterar todas as phases do torneio).
3. Pra cada match com `scheduledAt > now` e `status = SCHEDULED`, mostra formulário de palpite.
4. `PUT /api/tournaments/{tid}/matches/{mid}/predictions/me` salva o palpite. Same endpoint pra editar.
5. Após o deadline (`now >= scheduledAt`), exibe palpites de todos via `GET .../predictions`.
6. `GET /api/tournaments/{tid}/ranking` para mostrar a tabela de pontuação.

### Fluxo de lançar resultado (admin)

1. Match em `SCHEDULED` com `scheduledAt < now` (ou seja, deadline passou).
2. Owner abre a tela de lançar resultado.
3. `PUT /api/tournaments/{tid}/phases/{pid}/matches/{mid}/result` com `{ homeScore, awayScore }`.
4. Backend automaticamente recalcula pontos dos palpites.
5. Standings (`GET .../standings`) e ranking (`GET .../ranking`) refletem os novos pontos.
6. Owner pode editar o resultado a qualquer momento enquanto torneio está IN_PROGRESS — dispara recálculo de novo.

### Avançar entre phases

Quando todos os matches de uma phase estiverem resolvidos:

1. Owner chama `POST /api/tournaments/{tid}/phases/{pid}/finalize`.
2. Sistema processa as zones e popula `PhaseTeam` da próxima phase com os classificados.
3. Owner pode então gerar matches da próxima phase (`POST .../matches/generate`) ou criar manualmente.

### Encerrar torneio

`POST /api/tournaments/{id}/status` com `{ targetStatus: "FINISHED" }`. A partir daí, tudo fica read-only (não dá mais pra lançar resultado, palpitar, mudar nada).

---

## 22. Convenções e dicas

- **IDs em path params são sempre o UUID público** (`publicId`), nunca o `id` interno (Long).
- **Timestamps** sempre em UTC ISO 8601 com sufixo `Z`. Não há offset local.
- **Cores** sempre `#RRGGBB` em maiúscula ou minúscula — backend valida com regex.
- **Body de erros** sempre o mesmo `ApiError`. Tratar centralizado vale a pena.
- **Erros 409** muitas vezes têm mensagem técnica em inglês — vale criar um mapa de mensagem → texto amigável em pt-BR no frontend, ou exibir direto da API quando o public-facing.
- **Quando der 404** numa rota que envolve owner-context (ex.: GET `/api/teams/{id}` de um time alheio), é proposital — não vaza existência de recursos de outros usuários.
- **Spring `Page<>`**: o `pageable` interno traz mais info do que costuma ser usado; geralmente bastam `content`, `totalElements` e `totalPages` no front.
- **Refresh token** não fica em URL — sempre no body do `POST /api/auth/refresh`.
- **`Authorization` header não é necessário no `/signup`, `/signin`, `/refresh`** — qualquer cliente pode chamar. Para todo o resto, é obrigatório.
