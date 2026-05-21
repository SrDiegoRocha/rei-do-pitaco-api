CREATE TABLE predictions (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    tournament_id BIGINT NOT NULL REFERENCES tournaments (id) ON DELETE CASCADE,
    match_id BIGINT NOT NULL REFERENCES tournament_matches (id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users (id),
    home_score INTEGER NOT NULL,
    away_score INTEGER NOT NULL,
    points INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (user_id, match_id),
    CONSTRAINT chk_prediction_scores CHECK (home_score >= 0 AND away_score >= 0)
);

CREATE INDEX idx_predictions_tournament_id ON predictions (tournament_id);
CREATE INDEX idx_predictions_match_id ON predictions (match_id);
CREATE INDEX idx_predictions_user_id ON predictions (user_id);
CREATE INDEX idx_predictions_tournament_user ON predictions (tournament_id, user_id);
