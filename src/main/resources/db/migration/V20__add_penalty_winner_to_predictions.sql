-- Palpite de quem passa nos pênaltis (mata-mata jogo único ou perna de volta).
-- NULL = palpite não envolve pênaltis (palpite não-empate, ou partida não-mata-mata).
ALTER TABLE predictions
    ADD COLUMN penalty_winner VARCHAR(8);

ALTER TABLE predictions
    ADD CONSTRAINT chk_prediction_penalty_winner
        CHECK (penalty_winner IS NULL OR penalty_winner IN ('HOME', 'AWAY'));
