CREATE TABLE tournaments (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    owner_id BIGINT NOT NULL REFERENCES users (id),
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500),
    invite_code VARCHAR(8) NOT NULL UNIQUE,
    privacy VARCHAR(10) NOT NULL,
    type VARCHAR(20) NOT NULL,
    status VARCHAR(15) NOT NULL,
    max_participants INTEGER,
    max_teams INTEGER,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_tournaments_owner_id ON tournaments (owner_id);
CREATE INDEX idx_tournaments_public_id ON tournaments (public_id);
CREATE INDEX idx_tournaments_invite_code ON tournaments (invite_code);
CREATE INDEX idx_tournaments_status_privacy ON tournaments (status, privacy) WHERE active = TRUE;

CREATE TABLE tournament_settings (
    id BIGSERIAL PRIMARY KEY,
    tournament_id BIGINT NOT NULL UNIQUE REFERENCES tournaments (id) ON DELETE CASCADE,
    win_points INTEGER NOT NULL,
    draw_points INTEGER NOT NULL,
    loss_points INTEGER NOT NULL,
    exact_score_points INTEGER NOT NULL,
    winner_points INTEGER NOT NULL,
    wrong_points INTEGER NOT NULL,
    groups_count INTEGER,
    qualifiers_per_group INTEGER,
    plays_inside_group_only BOOLEAN,
    match_generation_mode VARCHAR(15) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE tournament_tiebreak_criteria (
    id BIGSERIAL PRIMARY KEY,
    tournament_id BIGINT NOT NULL REFERENCES tournaments (id) ON DELETE CASCADE,
    criteria VARCHAR(20) NOT NULL,
    position INTEGER NOT NULL,
    UNIQUE (tournament_id, criteria),
    UNIQUE (tournament_id, position)
);

CREATE INDEX idx_tiebreak_tournament_id ON tournament_tiebreak_criteria (tournament_id);

CREATE TABLE tournament_members (
    id BIGSERIAL PRIMARY KEY,
    tournament_id BIGINT NOT NULL REFERENCES tournaments (id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users (id),
    role VARCHAR(15) NOT NULL,
    status VARCHAR(10) NOT NULL,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL,
    left_at TIMESTAMP WITH TIME ZONE,
    banned_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (tournament_id, user_id)
);

CREATE INDEX idx_members_tournament_id ON tournament_members (tournament_id);
CREATE INDEX idx_members_user_id ON tournament_members (user_id);

CREATE TABLE tournament_teams (
    id BIGSERIAL PRIMARY KEY,
    tournament_id BIGINT NOT NULL REFERENCES tournaments (id) ON DELETE CASCADE,
    team_id BIGINT NOT NULL REFERENCES teams (id),
    added_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (tournament_id, team_id)
);

CREATE INDEX idx_tournament_teams_tournament_id ON tournament_teams (tournament_id);
CREATE INDEX idx_tournament_teams_team_id ON tournament_teams (team_id);
