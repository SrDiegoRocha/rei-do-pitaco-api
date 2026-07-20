---
name: verify
description: Como buildar, subir e dirigir a API do Rei do Pitaco localmente para verificar mudanças de ponta a ponta.
---

# Verificar a API localmente

## Build e run

- Maven não está no PATH — use o wrapper: `.\mvnw.cmd` (setar `$env:JAVA_HOME = "$env:USERPROFILE\.jdks\corretto-21.0.10"` antes).
- Banco dev: Postgres em `localhost:5434` (container `reidopitaco-postgres-dev`). O `.env.local` (gitignored, importado via `spring.config.import`) já aponta `DB_URL` pra lá e traz o `JWT_SECRET`.
- **NÃO tocar** no Postgres da porta `5433` (`reidopitaco-postgres`) — é o banco de PRODUÇÃO do self-host.
- A porta 8080 costuma estar ocupada por uma instância da IDE do usuário. Suba a instância de teste em outra porta:
  ```powershell
  $env:JAVA_HOME = "$env:USERPROFILE\.jdks\corretto-21.0.10"; $env:PORT = "8081"; .\mvnw.cmd -q spring-boot:run
  ```
  (em background; boot leva ~15-25s; health em `/actuator/health`)
- Flyway roda as migrations pendentes no boot — subir a app já testa as migrations novas contra o banco dev.
- Para derrubar a instância de teste: matar o processo que escuta a porta (`Get-NetTCPConnection -LocalPort 8081 -State Listen`).

## Dirigir a superfície

- Criar credencial: `POST /api/auth/signup` com `{name, email, password}` (email aleatório `verify-...@test.local`) → `accessToken`.
- Chamadas autenticadas: header `Authorization: Bearer <token>`.
- PowerShell 5.1 exibe JSON UTF-8 com mojibake (`BrasileirÃ£o`) — é só display; para conferir acentos de verdade, decodifique o stream com `[Text.Encoding]::UTF8`.

## Gotchas

- `GET /logos/**` é rota pública (escudos) servida do disco (`./logos` em dev).
- Exceções não mapeadas caem no handler genérico (500) do `GlobalExceptionHandler` — se um 4xx esperado vier como 500, provavelmente falta um `@ExceptionHandler` específico.
