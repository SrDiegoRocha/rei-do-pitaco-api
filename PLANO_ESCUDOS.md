# Plano — Escudos de clubes como times do sistema

> **Status: IMPLEMENTADO** (2026-07-20). Migrations V28/V29, scripts em `scripts/`, escudos em `logos/`, servidos em `GET /logos/**`. Documentação viva em `CLAUDE.md` (`# Escudos de clubes (self-hosted)`) e `API.md` (seção Times). Este arquivo fica como registro da decisão.

Baixar os escudos de clubes (brasileiros, europeus, etc.) do **football-logos.cc**, salvá-los no disco desta máquina (que é o servidor), servi-los pela própria API e cadastrar cada clube como **time do sistema** (`is_system = true`, `team_type = CLUB`), agrupado pela sua liga nacional — mesmo modelo das 50+ seleções que já existem (V17/V21/V22), só que com escudo em vez de bandeira.

---

## 1. A fonte: football-logos.cc

O que a investigação do site revelou (importante para o desenho do script):

- Coleção com ~3.475 times em 125 ligas. Formatos PNG e SVG, tamanhos de 64×64 até 3000×3000.
- O repositório GitHub associado (`Leo4815162342/football-logos`) é **apenas um índice/vitrine** — os PNGs que ele contém são banners sociais 1200×630 por país, **não** os escudos. Ou seja: não dá pra simplesmente clonar um repo; os arquivos reais vivem no CDN do site.
- URL real de um escudo: `https://assets.football-logos.cc/logos/{país}/{tamanho}/{slug}.{hash}.png`
  Ex.: `https://assets.football-logos.cc/logos/brazil/256x256/flamengo.9c3055f2.png`
- **O `{hash}` é um content-hash diferente para cada tamanho** (o 1500×1500 do Flamengo é `.9c5e3332`, o 256×256 é `.9c3055f2`). Não dá pra deduzir a URL — é preciso extrair do HTML.
- A **página de país** (ex. `https://football-logos.cc/brazil/`) lista **todos** os times do país **agrupados por liga** (Série A, Série B, ...), cada um com a `<img>` 256×256 já com hash. **Uma única requisição por país entrega slug + nome + liga + URL do escudo de todos os times.** É o caminho mais eficiente para o scraper.
- Para tamanhos maiores seria preciso visitar a página de cada time (1 request extra por time). **Decisão: 256×256 basta** — o front exibe escudos pequenos (listas, bracket, tabela); um PNG 256×256 tem ~5–20 KB. Se um dia precisar, o script aceita mudar o tamanho-alvo.

### Nota legal

A página `/license/` do site permite uso **informacional, editorial e em fan projects não-comerciais**, e proíbe uso comercial/merchandising. Os escudos continuam sendo marcas registradas dos clubes. Para o estágio atual do Rei do Pitaco (projeto pessoal, sem monetização) está adequado — **se o projeto virar produto pago, este ponto precisa ser revisitado** (mesma ressalva que já existe para o DiceBear nos avatares).

---

## 2. Decisões de arquitetura

### D1 — Escudos hospedados por nós, servidos pela própria API

- Os PNGs ficam em uma pasta `logos/` na **raiz do repositório**, organizada por país: `logos/brazil/flamengo.png`, `logos/england/arsenal.png`...
- **Commitados no git.** ~250–600 escudos 256×256 ≈ 5–15 MB — aceitável, e torna o deploy reproduzível (`git clone` + `docker compose up` já sobe tudo; dev e prod enxergam os mesmos arquivos).
- A API serve a pasta em `GET /logos/**` como recurso estático (sem JWT — é imagem pública). O Cloudflare Tunnel já publica a API em `api.pitaco.dpdns.org`, e o Cloudflare **cacheia imagens na borda por padrão**, então o custo de servir isso do seu PC é mínimo.

### D2 — `badge_url` relativo no banco, resolvido na leitura

Gravar `https://api.pitaco.dpdns.org/...` nas migrations amarraria o banco ao domínio (quebraria em dev). Em vez disso:

- No banco, os times do sistema guardam **caminho relativo**: `/logos/brazil/flamengo.png`.
- Na leitura, um componente novo (`AssetUrlResolver`, análogo ao `AvatarService`) prefixa com a base configurável: `reidopitaco.assets.base-url` (env `ASSETS_BASE_URL`; prod = `https://api.pitaco.dpdns.org`, dev = `http://localhost:8080`).
- Regra: `badgeUrl` que começa com `/` → prefixa; URL absoluta (times de usuário) → passa intacta. Nenhuma mudança de contrato para o front: `badgeUrl` continua chegando absoluto e pronto para o `<img>`.

### D3 — Liga como colunas novas em `teams`

Dois campos novos, preenchidos só nos clubes do sistema (NULL nos demais):

- `league_slug` (VARCHAR 60) — identificador estável para filtro, ex. `brasileirao-serie-a`, `premier-league`.
- `league_name` (VARCHAR 80) — nome de exibição, ex. `Brasileirão Série A`, `Premier League`.

O front agrupa o seletor de times por `league_name`; o filtro da API usa `league_slug`. O `country_code` (que já existe para as seleções) é **reutilizado nos clubes** com o país da liga (`br`, `gb-eng`, `es`, `it`, `de`, `fr`, `pt`, `nl`...) — o front pode mostrar a bandeirinha ao lado do nome da liga.

> Alternativa descartada por ora: tabela `leagues` normalizada com FK. Overkill para um catálogo estático; se um dia liga virar entidade com temporada/país/logo próprio, migra-se.

### D4 — Seed gerado por script, não escrito à mão

São ~250+ clubes; escrever o INSERT manualmente é inviável e propenso a erro. O scraper produz um `logos/manifest.json` e um segundo script gera a migration SQL a partir dele. O SQL gerado é **revisado manualmente** antes de commitar (nomes, colisões, cores).

---

## 3. Etapas de implementação

### Etapa 1 — Script de download (`scripts/download-logos.ts`)

Script standalone em TypeScript, rodado com `npx tsx scripts/download-logos.ts` (Node 20+, sem dependências além do `tsx`; `fetch` nativo). Se preferir zero-Node, dá pra portar para PowerShell — a lógica é a mesma.

1. Recebe a lista de países-alvo (constante no topo do script, ex. `['brazil', 'england', 'spain', ...]`) e opcionalmente um filtro de ligas por país (para pegar só Série A/B do Brasil e ignorar Série C/D).
2. Para cada país, baixa `https://football-logos.cc/{país}/` e extrai do HTML, por seção de liga:
   - nome da liga (heading da seção),
   - nome de exibição do time,
   - slug do time (do link `/{país}/{slug}/`),
   - URL do escudo 256×256 (src da `<img>`, com hash).
3. Baixa cada PNG para `logos/{país}/{slug}.png` (nome **sem** o hash — o hash só importa no CDN deles; localmente o arquivo é imutável).
   - Pula arquivos que já existem (re-execução barata/idempotente).
   - Delay de ~150 ms entre downloads (educação com o servidor deles; ~300 arquivos ≈ 1 min).
4. Grava/atualiza `logos/manifest.json`: lista de `{ country, countryCode, leagueSlug, leagueName, teamSlug, teamName, file }`.

### Etapa 2 — Gerador do seed (`scripts/generate-seed.ts`)

Lê o `manifest.json` e escreve `src/main/resources/db/migration/V29__seed_system_clubs.sql` no mesmo padrão da V17:

- `INSERT INTO teams (public_id, name, short_name, badge_url, primary_color, secondary_color, country_code, league_slug, league_name, is_system, team_type, active, created_at, updated_at)`
- `name`: nome vindo do site (revisão manual para ajustar acentuação/PT-BR onde fizer sentido — ex. "Sao Paulo" → "São Paulo").
- `short_name`: heurística de 3 letras maiúsculas (ex. FLA, ARS) gerada pelo script, **revisada à mão** — ou NULL onde não houver sigla óbvia.
- `badge_url`: caminho relativo `/logos/{país}/{slug}.png`.
- `primary_color`/`secondary_color`: são NOT NULL no schema. O site não fornece cores. Default `#1F2937`/`#F9FAFB` no gerado, com ajuste manual dos clubes grandes. (Nice-to-have futuro: o script extrair as 2 cores dominantes do PNG.)
- `team_type = 'CLUB'`, `is_system = TRUE`, `owner_id = NULL`.

**Colisão de nomes**: existe índice único `uq_teams_system_name_active` em `LOWER(name)` entre times do sistema ativos. Clubes homônimos de ligas diferentes (ex. "Nacional" em Portugal vs. Uruguai, "Barcelona" ESP vs. EQU) e clubes vs. seleções precisam ser desambiguados. O gerador **detecta duplicatas** (contra o manifest inteiro + a lista de seleções já seedadas) e qualifica o nome automaticamente (ex. "Nacional (POR)"), sinalizando no console para revisão.

### Etapa 3 — Backend

1. **Migration `V28__add_league_to_teams.sql`**: `ALTER TABLE teams ADD COLUMN league_slug VARCHAR(60), ADD COLUMN league_name VARCHAR(80);` + índice em `league_slug` (parcial, `WHERE is_system AND active`). A V29 (seed gerado) vem em seguida.
2. **Servir estáticos**:
   - `AssetsProperties` (`reidopitaco.assets.base-url` e `reidopitaco.assets.logos-dir`, envs `ASSETS_BASE_URL` / `LOGOS_DIR`; defaults `http://localhost:8080` e `./logos`).
   - `WebConfig.addResourceHandlers`: `/logos/**` → `file:{logos-dir}/`, com `Cache-Control` de 7 dias (os arquivos são estáveis; se um escudo for trocado, troca-se o nome do arquivo).
   - `SecurityConfig`: `permitAll` para `GET /logos/**`.
3. **`AssetUrlResolver`** (service): `resolve(badgeUrl)` — prefixa a base quando o valor começa com `/`. Aplicado em **todos** os pontos que montam `badgeUrl` hoje: `TeamMapper` (TeamResponse), `TournamentTeamResponse`, `MatchMapper.toTeamRef` (partidas/bracket), `PhaseTeamResponse` e `StandingsService` (StandingRow).
4. **Entity/DTOs**: campos `leagueSlug`/`leagueName` na `Team` e no `TeamResponse` (e no `TournamentTeamResponse`, para o front agrupar o seletor). Nos DTOs de partida (`TeamRef`) não precisa — liga não aparece em placar.
5. **Filtro na listagem**: `GET /api/teams?scope=system&type=CLUB&league={leagueSlug}` — novo parâmetro opcional no `TeamController` + `TeamRepository.search`. Sem `league`, comportamento atual intacto.
6. **Proteção existente já cobre o resto**: PUT/DELETE em time do sistema já retorna 403; vínculo a torneio já aceita time do sistema; `PhaseFinalizeService` já resolve times sem escopar por dono. Nada a mudar.

### Etapa 4 — Deploy (docker-compose.selfhost.yml)

- Volume novo no serviço `api`: `./logos:/app/logos:ro`.
- No `.env` do servidor: `ASSETS_BASE_URL=https://api.pitaco.dpdns.org` e `LOGOS_DIR=/app/logos`.
- Nada muda no túnel: `/logos/**` sai pelo mesmo hostname da API e o Cloudflare cacheia as imagens na borda.
- Deploy: `git pull` (traz migrations + pasta `logos/`) e `docker compose -f docker-compose.selfhost.yml up -d --build` — o Flyway roda V28/V29 na subida.

### Etapa 5 — Validação e documentação

- `GET /logos/brazil/flamengo.png` sem token → 200 com a imagem (local e via `api.pitaco.dpdns.org`).
- `GET /api/teams?scope=system&type=CLUB` → clubes com `badgeUrl` absoluto e `leagueSlug`/`leagueName` preenchidos.
- `GET /api/teams?scope=system&type=CLUB&league=brasileirao-serie-a` → só os 20 da Série A.
- Vincular um clube do sistema a um torneio, gerar partidas e conferir o escudo no `TeamRef` de match/bracket/standings.
- Atualizar **API.md** (contrato com o front: campos novos, filtro `league`, rota pública `/logos/**`) e **CLAUDE.md** (seção `# Times do sistema`: clubes agora existem, escudo via `badgeUrl` self-hosted, liga).

---

## 4. Lote inicial de ligas

| País (slug do site) | Liga | `league_slug` | ~Times |
| --- | --- | --- | --- |
| brazil | Brasileirão Série A | `brasileirao-serie-a` | 20 |
| brazil | Brasileirão Série B | `brasileirao-serie-b` | 20 |
| england | Premier League | `premier-league` | 20 |
| spain | La Liga | `la-liga` | 20 |
| italy | Serie A | `serie-a` | 20 |
| germany | Bundesliga | `bundesliga` | 18 |
| france | Ligue 1 | `ligue-1` | 18 |
| portugal | Primeira Liga | `primeira-liga` | 18 |
| netherlands | Eredivisie | `eredivisie` | 18 |
| argentina | Primera División | `primera-division-arg` | ~28 |

**Total ≈ 200 clubes / ~2–5 MB de PNGs.** Adicionar uma liga depois = incluir o país/liga na constante do script, rodar de novo (só baixa o que falta) e gerar uma migration nova (`V30__seed_more_clubs.sql`) — o processo é incremental por desenho.

---

## 5. Pontos de atenção

- **Licença**: só uso não-comercial (ver Nota legal acima).
- **Site pode mudar o HTML**: o scraper é one-shot — depois do download os escudos são nossos, localmente. Se o site mudar, só afeta rodadas futuras de novas ligas (ajusta-se o parser).
- **Cores NOT NULL**: seed sai com cores default; curadoria manual dos clubes grandes na revisão do SQL.
- **`short_name` heurístico**: revisar as siglas geradas (ex. evitar "SÃO" para São Paulo — o certo é "SAO" ou "SPF").
- **Nomes duplicados**: gerador desambigua automaticamente, mas a revisão manual do SQL é obrigatória antes de commitar.
- **Tamanho do repo**: +5–15 MB de binários no git. Aceitável hoje; se incomodar no futuro, mover para Git LFS ou para um zip versionado fora do repo (o volume do Docker não muda).

## 6. Fora de escopo (roadmap)

- Extração automática de cores dominantes do PNG no gerador de seed.
- Endpoint admin para re-sincronizar/adicionar ligas sem migration.
- SVG em vez de PNG (menor e escala infinita, porém exige sanitização de SVG antes de servir).
- Upload de escudo customizado para times de usuário (hoje seguem com `badgeUrl` externo).
