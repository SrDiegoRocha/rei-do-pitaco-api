CREATE TABLE teams (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    owner_id BIGINT NOT NULL REFERENCES users (id),
    name VARCHAR(80) NOT NULL,
    short_name VARCHAR(5),
    badge_url VARCHAR(500),
    primary_color VARCHAR(7) NOT NULL,
    secondary_color VARCHAR(7) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_teams_owner_id ON teams (owner_id);
CREATE INDEX idx_teams_public_id ON teams (public_id);

CREATE UNIQUE INDEX uq_teams_owner_name_active
    ON teams (owner_id, LOWER(name))
    WHERE active = TRUE;
