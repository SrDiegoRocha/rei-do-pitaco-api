-- Chaveamento opcional no mata-mata: FIXED_BRACKET (arvore fixa definida pelo sorteio da
-- 1a rodada) ou REDRAW_EACH_ROUND (vencedores sorteados de novo a cada rodada, estilo fases
-- iniciais da Copa do Brasil). So se aplica a fases KNOCKOUT; NULL nas demais.
ALTER TABLE tournament_phases
    ADD COLUMN bracket_mode VARCHAR(20);

-- Backfill preservando o comportamento anterior: a geracao AUTOMATIC ja emparelhava os
-- vencedores em ordem canonica (chaveamento fixo); fases MANUAL eram livres (sem validacao
-- de chaveamento), entao ficam sem chaveamento.
UPDATE tournament_phases
SET bracket_mode = CASE
    WHEN match_generation_mode = 'MANUAL' THEN 'REDRAW_EACH_ROUND'
    ELSE 'FIXED_BRACKET'
END
WHERE phase_type = 'KNOCKOUT';
