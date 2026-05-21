ALTER TABLE tournament_phases
    ADD COLUMN has_third_place BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE tournament_phases
    ALTER COLUMN has_third_place DROP DEFAULT;
