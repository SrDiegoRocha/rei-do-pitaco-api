-- Pontuação configurável dos novos componentes de palpite, somados aos pontos do tempo normal:
--   extra_time_exact_score_points  -> acertar o placar exato da prorrogação   (default 2)
--   extra_time_winner_points       -> errar o placar mas acertar quem vence a prorrogação (default 1)
--   penalty_winner_points          -> acertar quem passa nos pênaltis          (default 2)
-- NOT NULL com DEFAULT constante é operação metadata-only no Postgres (>= 11): não reescreve a
-- tabela e preenche as linhas existentes com o default. Seguro para rodar em produção.

ALTER TABLE tournament_settings
    ADD COLUMN extra_time_exact_score_points INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN extra_time_winner_points INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN penalty_winner_points INTEGER NOT NULL DEFAULT 2;
