-- V10: Create pump_shift_definitions table.
--
-- Replaces the hardcoded ShiftWindow enum (SHIFT_1/2/3) with admin-configurable
-- shift definitions per pump. Admins can define up to 4 shifts with arbitrary
-- time windows. At least one must be flagged as the night shift and must overlap
-- the 00:00–06:00 window.
--
-- effective_from / effective_to allow versioning: when an admin reconfigures shifts
-- mid-operation, historical Shift records retain their original shiftDefinitionId
-- so reports remain accurate.

CREATE TABLE pump_shift_definitions (
    id                  BIGSERIAL       PRIMARY KEY,
    pump_id             BIGINT          NOT NULL,
    name                VARCHAR(100)    NOT NULL,
    start_time          TIME            NOT NULL,
    end_time            TIME            NOT NULL,
    crosses_midnight    BOOLEAN         NOT NULL DEFAULT FALSE,
    is_night_shift      BOOLEAN         NOT NULL DEFAULT FALSE,
    sort_order          INTEGER         NOT NULL DEFAULT 1,
    effective_from      DATE            NOT NULL,
    effective_to        DATE,                        -- NULL = currently active
    created_by_user_id  BIGINT          NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_psd_pump         FOREIGN KEY (pump_id)            REFERENCES pump_locations(id) ON DELETE CASCADE,
    CONSTRAINT fk_psd_created_by   FOREIGN KEY (created_by_user_id) REFERENCES users(id)
);

-- Index to efficiently find active definitions for a pump on a given date
CREATE INDEX idx_psd_pump_effective ON pump_shift_definitions (pump_id, effective_from, effective_to);
