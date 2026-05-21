CREATE TABLE tournament_matches (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    phase_id BIGINT NOT NULL REFERENCES tournament_phases (id) ON DELETE CASCADE,
    group_id BIGINT REFERENCES phase_groups (id) ON DELETE SET NULL,
    round INTEGER NOT NULL,
    tie_id UUID NOT NULL,
    home_team_id BIGINT NOT NULL REFERENCES teams (id),
    away_team_id BIGINT NOT NULL REFERENCES teams (id),
    scheduled_at TIMESTAMP WITH TIME ZONE,
    home_score INTEGER,
    away_score INTEGER,
    status VARCHAR(15) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_match_teams_distinct CHECK (home_team_id <> away_team_id)
);

CREATE INDEX idx_matches_phase_id ON tournament_matches (phase_id);
CREATE INDEX idx_matches_group_id ON tournament_matches (group_id);
CREATE INDEX idx_matches_tie_id ON tournament_matches (tie_id);
CREATE INDEX idx_matches_phase_round ON tournament_matches (phase_id, round);

CREATE TABLE tournament_zones (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    phase_id BIGINT NOT NULL REFERENCES tournament_phases (id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL,
    from_position INTEGER NOT NULL,
    to_position INTEGER NOT NULL,
    selection_mode VARCHAR(15) NOT NULL,
    best_ranked_count INTEGER,
    next_phase_id BIGINT REFERENCES tournament_phases (id) ON DELETE SET NULL,
    position INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (phase_id, position),
    CONSTRAINT chk_zone_positions CHECK (from_position <= to_position AND from_position >= 1),
    CONSTRAINT chk_zone_best_ranked CHECK (
        (selection_mode = 'ALL' AND best_ranked_count IS NULL)
        OR (selection_mode = 'BEST_RANKED' AND best_ranked_count IS NOT NULL AND from_position = to_position)
    )
);

CREATE INDEX idx_zones_phase_id ON tournament_zones (phase_id);
CREATE INDEX idx_zones_next_phase_id ON tournament_zones (next_phase_id);
