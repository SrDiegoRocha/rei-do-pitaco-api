# Pick'em — pedido do front para a API

> **✅ Atendido pelo backend.** `GET /api/users/me/pickems/pending` implementado conforme o contrato abaixo (mesmo guard do `template` via `PhasePredictionContextService`, ordenação por urgência com nulls por último) e documentado no `API.md` **§20.9**. Verificado com E2E contra a API real: `[]` sem torneios, torneio `OPEN` não gera pendência, fase KO `NOT_READY` fica de fora, owner (member auto) vê pendência, some após o upsert e após a trava pelo 1º resultado.

O front do Pick'em (Palpitão) está pronto e integrado a **todos** os endpoints da §20 do `API.md`. Falta **um endpoint novo** para o card da home/feed funcionar. Enquanto ele não existir, o front degrada graciosamente: a chamada falha (404) e o card simplesmente não aparece — nada quebra.

---

## `GET /api/users/me/pickems/pending` → 200

**Objetivo de produto:** na tela inicial (feed de partidas), mostrar um card "Palpitão aberto" listando as fases em que o usuário **ainda pode e ainda não fez** o Pick'em — o mesmo espírito do `GET /api/users/me/matches/pending-count` (badge "X jogos esperando seu pitaco"), mas para o Pick'em de fase.

### Contrato

Sem body, sem query params. `Authorization: Bearer <access-token>` (basta estar autenticado — escopado ao próprio usuário, igual ao feed pessoal §14.1).

Resposta: **array simples** (sem paginação — a lista é naturalmente curta), ordenado por urgência (ver abaixo).

```ts
export interface PendingPickemResponse {
  tournamentId: string;        // UUID público do torneio
  tournamentName: string;
  phaseId: string;             // UUID público da fase
  phaseName: string;
  phaseType: 'ROUND_ROBIN' | 'GROUPS' | 'KNOCKOUT';
  lockAt: string | null;       // min(scheduledAt) das partidas da fase (ISO);
                               // null = trava quando o 1º resultado for lançado
}
```

### Quais fases entram (todas as condições ao mesmo tempo)

1. Torneio **ativo** (não soft-deletado) e **`IN_PROGRESS`**, onde o usuário é **member `ACTIVE`**.
2. O Pick'em da fase está **`OPEN`** — exatamente a mesma regra do `GET .../pickem/template` (§20.1): substrato pronto (times/grupos/zonas na tabela, 1ª rodada gerada no KO) **e** ainda não travou (`now < min(scheduledAt)`, ou nenhum resultado lançado quando não há horários). Reusar o guard existente (`PhasePredictionContextService`), não reimplementar.
3. O usuário **ainda não tem** `PhasePrediction` naquela fase (quem já palpitou sai da lista — o card é um call-to-action, não um resumo).

### Ordenação

Por urgência: `lockAt` ascendente com **nulls por último** (fase prestes a travar aparece primeiro); empate → `tournamentName` asc, depois posição da fase.

### Erros

Nenhum caso especial: usuário sem pendências → `[]` (200). Token ausente/inválido → 401/403 padrão.

### Observações de implementação

- É uma leitura potencialmente N+1 (torneios × fases). Como o universo por usuário é pequeno (poucos torneios ativos), uma query por memberships ativos + fases com o guard aplicado em memória resolve; não precisa de materialização.
- **Não** incluir fases de torneios em `OPEN`/`DRAFT`/`FINISHED` — o template delas responde `NOT_READY`/travado e não são acionáveis.

---

## Uso no front (já implementado, aguardando o endpoint)

- `PickemService.pendingForMe()` chama o endpoint no load da home (`matches-feed`).
- Cada item vira uma linha do card com link para `/tournaments/{tournamentId}/phases/{phaseId}/pickem` e o texto "trava {data}" (ou "trava no 1º resultado" quando `lockAt = null`).
- Erro em qualquer status (incluindo 404 enquanto o endpoint não existe) → card oculto.

Nada mais é necessário do backend: aba Palpitão, página da fase (template/upsert/listagem/stats/recalculate), perfil do participante (`summary`) e os 8 campos `pickem*` no settings já estão cobertos pelo contrato atual do `API.md`.
