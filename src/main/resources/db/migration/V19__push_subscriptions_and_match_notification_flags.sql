-- Inscrições Web Push (uma por dispositivo/endpoint). Vinculadas ao usuário;
-- removidas em cascata se o usuário for apagado.
CREATE TABLE push_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    endpoint TEXT NOT NULL UNIQUE,
    p256dh TEXT NOT NULL,
    auth TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_push_subscriptions_user_id ON push_subscriptions (user_id);

-- Flags que garantem que cada aviso (24h/4h/1h/resultado) seja enviado uma única vez por partida.
ALTER TABLE tournament_matches
    ADD COLUMN notified_24h    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN notified_4h     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN notified_1h     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN notified_result BOOLEAN NOT NULL DEFAULT FALSE;
