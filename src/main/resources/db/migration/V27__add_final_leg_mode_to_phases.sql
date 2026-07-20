-- Modo de pernas da RODADA FINAL de uma fase KNOCKOUT (SINGLE/TWO_LEGGED), decidido pelo admin
-- independentemente do match_leg_mode da fase. Vale para a final E para a disputa de 3º lugar
-- (que acontece na mesma rodada). NULL = herda o match_leg_mode da fase (comportamento atual —
-- retrocompatível). Só tem efeito em fases KNOCKOUT.

ALTER TABLE tournament_phases
    ADD COLUMN final_leg_mode VARCHAR(15);
