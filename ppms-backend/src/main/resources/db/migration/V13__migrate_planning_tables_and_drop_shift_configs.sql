-- V13: Migrate planning tables to use shift_definition_id; drop pump_shift_configs.
--
-- shift_plan_entry.shift_window (PostgreSQL enum) → dropped; replaced by shift_definition_id.
-- staff_preference.preferred_shift (PostgreSQL enum) → dropped; replaced by preferred_shift_definition_id.
-- pump_shift_configs is removed entirely (superseded by pump_shift_definitions).

-- ── shift_plan_entry ──────────────────────────────────────────────────────────

-- Truncate planning entries (shift windows are being replaced; plans need to be
-- recreated against the new dynamic definitions by the admin)
TRUNCATE TABLE shift_plan_entry RESTART IDENTITY CASCADE;

-- Convert and drop the old enum column
ALTER TABLE shift_plan_entry
    ALTER COLUMN shift_window TYPE VARCHAR(100) USING shift_window::text;

ALTER TABLE shift_plan_entry DROP COLUMN shift_window;

-- Add the new FK column
ALTER TABLE shift_plan_entry
    ADD COLUMN shift_definition_id BIGINT
    REFERENCES pump_shift_definitions(id);

CREATE INDEX idx_spe_definition ON shift_plan_entry (shift_definition_id);

-- ── staff_preference ──────────────────────────────────────────────────────────

-- Convert and drop the old enum column
ALTER TABLE staff_preference
    ALTER COLUMN preferred_shift TYPE VARCHAR(100) USING preferred_shift::text;

ALTER TABLE staff_preference DROP COLUMN preferred_shift;

-- Add the new nullable FK (null = no shift preference / any shift is fine)
ALTER TABLE staff_preference
    ADD COLUMN preferred_shift_definition_id BIGINT
    REFERENCES pump_shift_definitions(id);

-- ── pump_shift_configs ────────────────────────────────────────────────────────

-- Drop the old per-pump 3-window config table; pump_shift_definitions replaces it
DROP TABLE IF EXISTS pump_shift_configs;
