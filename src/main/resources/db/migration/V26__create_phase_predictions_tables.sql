-- Pick'em de fase: palpite de alto nível sobre o desfecho de uma fase inteira, feito antes de a
-- fase começar. A "capa" (phase_predictions) guarda um palpite por usuário/fase com os pontos
-- materializados; o detalhe fica em duas tabelas filhas conforme o tipo da fase:
--   phase_prediction_positions -> tabela/grupos (ROUND_ROBIN/GROUPS): time X na posição Y
--   phase_prediction_ties      -> chaveamento (KNOCKOUT): confronto previsto + quem avança

CREATE TABLE phase_predictions (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    tournament_id BIGINT NOT NULL REFERENCES tournaments (id) ON DELETE CASCADE,
    phase_id BIGINT NOT NULL REFERENCES tournament_phases (id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users (id),
    phase_type VARCHAR(15) NOT NULL,
    points INTEGER NOT NULL DEFAULT 0,
    scored_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (user_id, phase_id)
);

CREATE INDEX idx_phase_predictions_tournament_id ON phase_predictions (tournament_id);
CREATE INDEX idx_phase_predictions_phase_id ON phase_predictions (phase_id);
CREATE INDEX idx_phase_predictions_tournament_user ON phase_predictions (tournament_id, user_id);

CREATE TABLE phase_prediction_positions (
    id BIGSERIAL PRIMARY KEY,
    phase_prediction_id BIGINT NOT NULL REFERENCES phase_predictions (id) ON DELETE CASCADE,
    group_id BIGINT REFERENCES phase_groups (id) ON DELETE CASCADE,
    team_id BIGINT NOT NULL REFERENCES teams (id),
    predicted_position INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_ppp_position CHECK (predicted_position >= 1)
);

CREATE INDEX idx_ppp_prediction_id ON phase_prediction_positions (phase_prediction_id);

-- Unicidade de posição e de time dentro do mesmo bloco (grupo, ou bloco único em ROUND_ROBIN).
-- Índices parciais porque um UNIQUE normal trataria linhas com group_id NULL como sempre distintas.
CREATE UNIQUE INDEX uq_ppp_position_group ON phase_prediction_positions (phase_prediction_id, group_id, predicted_position)
    WHERE group_id IS NOT NULL;
CREATE UNIQUE INDEX uq_ppp_position_nogroup ON phase_prediction_positions (phase_prediction_id, predicted_position)
    WHERE group_id IS NULL;
CREATE UNIQUE INDEX uq_ppp_team_group ON phase_prediction_positions (phase_prediction_id, group_id, team_id)
    WHERE group_id IS NOT NULL;
CREATE UNIQUE INDEX uq_ppp_team_nogroup ON phase_prediction_positions (phase_prediction_id, team_id)
    WHERE group_id IS NULL;

CREATE TABLE phase_prediction_ties (
    id BIGSERIAL PRIMARY KEY,
    phase_prediction_id BIGINT NOT NULL REFERENCES phase_predictions (id) ON DELETE CASCADE,
    round_number INTEGER NOT NULL,
    slot_index INTEGER NOT NULL,
    match_type VARCHAR(15) NOT NULL DEFAULT 'REGULAR',
    predicted_home_team_id BIGINT NOT NULL REFERENCES teams (id),
    predicted_away_team_id BIGINT NOT NULL REFERENCES teams (id),
    predicted_winner_team_id BIGINT NOT NULL REFERENCES teams (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (phase_prediction_id, round_number, slot_index, match_type),
    CONSTRAINT chk_ppt_round CHECK (round_number >= 1),
    CONSTRAINT chk_ppt_slot CHECK (slot_index >= 0),
    CONSTRAINT chk_ppt_distinct_teams CHECK (predicted_home_team_id <> predicted_away_team_id),
    CONSTRAINT chk_ppt_winner_in_pair CHECK (
        predicted_winner_team_id = predicted_home_team_id
        OR predicted_winner_team_id = predicted_away_team_id
    )
);

CREATE INDEX idx_ppt_prediction_id ON phase_prediction_ties (phase_prediction_id);
