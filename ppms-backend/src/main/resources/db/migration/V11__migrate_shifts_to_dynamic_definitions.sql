-- V11: Migrate shifts table to use dynamic shift definitions.
--
-- All shift data is truncated (owner decision — clean slate).
-- shifts.shift_window (PostgreSQL enum) is converted to VARCHAR to store a
-- human-readable name snapshot (e.g. "Night Shift") so historical records
-- display correctly even if the definition is later renamed.
-- New columns: shift_definition_id (FK), shift_name (snapshot), is_night_shift (snapshot).

-- Truncate in FK-safe order (child tables first)
TRUNCATE TABLE shift_credit_entries RESTART IDENTITY CASCADE;
TRUNCATE TABLE shift_fuel_readings  RESTART IDENTITY CASCADE;
TRUNCATE TABLE shifts               RESTART IDENTITY CASCADE;

-- Convert shift_window from PostgreSQL named enum → VARCHAR (name snapshot storage)
ALTER TABLE shifts
    ALTER COLUMN shift_window TYPE VARCHAR(100) USING shift_window::text;

-- Rename to reflect its new purpose: storing a snapshot of the shift's display name
ALTER TABLE shifts RENAME COLUMN shift_window TO shift_name;

-- shift_name is now nullable (set at shift-open time from the matched definition)
ALTER TABLE shifts ALTER COLUMN shift_name DROP NOT NULL;

-- FK to the definition that was active when this shift was opened
-- Nullable to allow the column to exist before a definition is assigned
ALTER TABLE shifts
    ADD COLUMN shift_definition_id BIGINT
    REFERENCES pump_shift_definitions(id);

-- Snapshot of the night-shift flag at open time — used by payroll calculations
-- without needing to re-join the definition table
ALTER TABLE shifts
    ADD COLUMN is_night_shift BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_shifts_definition      ON shifts (shift_definition_id);
CREATE INDEX idx_shifts_pump_date_def   ON shifts (pump_id, shift_date, shift_definition_id);
