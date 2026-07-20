-- Liga nacional dos clubes do sistema (V29 faz o seed). NULL nos times de
-- usuário e nas seleções — liga só se aplica a clube do sistema.
ALTER TABLE teams
    ADD COLUMN league_slug VARCHAR(60),
    ADD COLUMN league_name VARCHAR(80);

-- Filtro por liga na listagem de times do sistema (GET /api/teams?league=...).
CREATE INDEX idx_teams_league_slug
    ON teams (league_slug)
    WHERE is_system = TRUE AND active = TRUE;
