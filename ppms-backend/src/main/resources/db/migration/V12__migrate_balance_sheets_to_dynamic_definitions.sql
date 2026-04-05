-- V12: Truncate balance sheet data and add shift_definition_id.
--
-- balance_sheets.shift_window was already converted to VARCHAR in V8.
-- It now stores the shift name snapshot (e.g. "Night Shift") instead of "SHIFT_1".
-- shift_definition_id is added to group SHIFT-type reports by their definition.

-- Truncate in FK-safe order
TRUNCATE TABLE bs_shift_lines   RESTART IDENTITY CASCADE;
TRUNCATE TABLE bs_fuel_lines    RESTART IDENTITY CASCADE;
TRUNCATE TABLE balance_sheets   RESTART IDENTITY CASCADE;

ALTER TABLE balance_sheets
    ADD COLUMN shift_definition_id BIGINT
    REFERENCES pump_shift_definitions(id);

CREATE INDEX idx_bs_definition ON balance_sheets (shift_definition_id);
