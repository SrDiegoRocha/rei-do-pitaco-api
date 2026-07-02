-- Placar da prorrogação (extra time) para mata-mata de JOGO ÚNICO (SINGLE) do KNOCKOUT.
-- Cumulativo: inclui os gols do tempo normal, logo o placar da prorrogação nunca pode ser
-- menor que o do tempo normal por time (ex.: 1x1 no normal => mínimo 1x1 na prorrogação).
-- Colunas nullable e CHECK null-safe: linhas existentes (ET = NULL) passam pela primeira branch.

ALTER TABLE tournament_matches
    ADD COLUMN home_extra_time_score INTEGER,
    ADD COLUMN away_extra_time_score INTEGER;

ALTER TABLE tournament_matches
    ADD CONSTRAINT chk_match_extra_time CHECK (
        (home_extra_time_score IS NULL AND away_extra_time_score IS NULL)
        OR (
            home_extra_time_score IS NOT NULL AND away_extra_time_score IS NOT NULL
            AND home_score IS NOT NULL AND away_score IS NOT NULL
            AND home_extra_time_score >= 0 AND away_extra_time_score >= 0
            AND home_extra_time_score >= home_score
            AND away_extra_time_score >= away_score
        )
    );

ALTER TABLE predictions
    ADD COLUMN home_extra_time_score INTEGER,
    ADD COLUMN away_extra_time_score INTEGER;

ALTER TABLE predictions
    ADD CONSTRAINT chk_prediction_extra_time CHECK (
        (home_extra_time_score IS NULL AND away_extra_time_score IS NULL)
        OR (
            home_extra_time_score IS NOT NULL AND away_extra_time_score IS NOT NULL
            AND home_extra_time_score >= home_score
            AND away_extra_time_score >= away_score
        )
    );
