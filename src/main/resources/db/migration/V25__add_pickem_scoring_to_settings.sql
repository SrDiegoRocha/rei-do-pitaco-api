-- Pontuação configurável do Pick'em de fase (palpite de alto nível feito antes de cada fase começar).
-- Componentes de tabela (ROUND_ROBIN/GROUPS):
--   pickem_qualifier_points          -> time previsto que termina classificado (qualifies real)
--   pickem_exact_position_points     -> time cravado na posição final exata
--   pickem_first_place_points        -> acertar o 1º da tabela/grupo
-- Componentes de bracket (KNOCKOUT):
--   pickem_ko_matchup_exact_points   -> cravar os dois times de um confronto (A+B)
--   pickem_ko_matchup_partial_points -> acertar pelo menos 1 time do confronto (exclusivo com o exato)
--   pickem_champion_points           -> acertar o campeão
--   pickem_runner_up_points          -> acertar o vice
--   pickem_third_place_points        -> acertar o 3º lugar (só quando a fase tem disputa de 3º)
-- NOT NULL com DEFAULT constante é operação metadata-only no Postgres (>= 11): não reescreve a
-- tabela e preenche as linhas existentes com o default. Seguro para rodar em produção.

ALTER TABLE tournament_settings
    ADD COLUMN pickem_qualifier_points INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN pickem_exact_position_points INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN pickem_first_place_points INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN pickem_ko_matchup_exact_points INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN pickem_ko_matchup_partial_points INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN pickem_champion_points INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN pickem_runner_up_points INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN pickem_third_place_points INTEGER NOT NULL DEFAULT 1;
