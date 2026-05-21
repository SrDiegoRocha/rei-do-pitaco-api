ALTER TABLE tournament_settings
    ADD COLUMN match_leg_mode VARCHAR(15) NOT NULL DEFAULT 'SINGLE';

ALTER TABLE tournament_settings
    ALTER COLUMN match_leg_mode DROP DEFAULT;
