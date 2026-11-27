-- Shift operations (blind cash count, several operators on a shift, nozzle swaps, relief) and staff cash advances
-- recovered through payroll. New enum values cannot be used in the transaction that adds them, so no statement below
-- names them.

-- 1. Blind cash count: the collections counted before the expected amount is shown. Either every figure is set or none.
ALTER TABLE pump_locations ADD COLUMN blind_cash_count BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE shifts
    ADD COLUMN blind_cash_counted        NUMERIC(12,2),
    ADD COLUMN blind_upi_counted         NUMERIC(12,2),
    ADD COLUMN blind_card_counted        NUMERIC(12,2),
    ADD COLUMN blind_fleet_card_counted  NUMERIC(12,2),
    ADD COLUMN blind_cash_denominations  JSONB,
    ADD COLUMN blind_counted_at          TIMESTAMPTZ,
    ADD COLUMN blind_counted_by_user_id  BIGINT,
    ADD CONSTRAINT fk_shift_blind_counted_by FOREIGN KEY (blind_counted_by_user_id) REFERENCES users (id),
    ADD CONSTRAINT chk_shift_blind_count_together CHECK (
        (blind_counted_at IS NULL AND blind_counted_by_user_id IS NULL AND blind_cash_counted IS NULL
            AND blind_upi_counted IS NULL AND blind_card_counted IS NULL AND blind_fleet_card_counted IS NULL
            AND blind_cash_denominations IS NULL)
        OR (blind_counted_at IS NOT NULL AND blind_counted_by_user_id IS NOT NULL AND blind_cash_counted IS NOT NULL
            AND blind_upi_counted IS NOT NULL AND blind_card_counted IS NOT NULL AND blind_fleet_card_counted IS NOT NULL)),
    ADD CONSTRAINT chk_shift_blind_count_not_negative CHECK (
        (blind_cash_counted IS NULL OR blind_cash_counted >= 0)
        AND (blind_upi_counted IS NULL OR blind_upi_counted >= 0)
        AND (blind_card_counted IS NULL OR blind_card_counted >= 0)
        AND (blind_fleet_card_counted IS NULL OR blind_fleet_card_counted >= 0));

-- 2. Operators working a shift besides the one it was opened for: ADDITIONAL for the whole shift, RELIEF while
--    covering someone's break. An operator is on at most one shift at a time.
CREATE TABLE shift_operators (
    id                  BIGSERIAL    PRIMARY KEY,
    shift_id            BIGINT       NOT NULL,
    operator_id         BIGINT       NOT NULL,
    role                VARCHAR(20)  NOT NULL,
    joined_at           TIMESTAMPTZ  NOT NULL,
    left_at             TIMESTAMPTZ,
    added_by_user_id    BIGINT       NOT NULL,

    CONSTRAINT fk_shift_operator_shift    FOREIGN KEY (shift_id)         REFERENCES shifts (id),
    CONSTRAINT fk_shift_operator_operator FOREIGN KEY (operator_id)      REFERENCES users (id),
    CONSTRAINT fk_shift_operator_added_by FOREIGN KEY (added_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_shift_operator_role    CHECK (role IN ('ADDITIONAL', 'RELIEF')),
    CONSTRAINT chk_shift_operator_period  CHECK (left_at IS NULL OR left_at >= joined_at)
);

CREATE UNIQUE INDEX uq_shift_operator_on_one_shift ON shift_operators (operator_id) WHERE left_at IS NULL;
CREATE INDEX idx_shift_operator_shift ON shift_operators (shift_id);

-- 3. A relief: one operator covers another's nozzles during a break, without closing the shift.
CREATE TABLE shift_reliefs (
    id                    BIGSERIAL      PRIMARY KEY,
    shift_id              BIGINT         NOT NULL,
    relieved_operator_id  BIGINT         NOT NULL,
    relief_operator_id    BIGINT         NOT NULL,
    started_at            TIMESTAMPTZ    NOT NULL,
    ended_at              TIMESTAMPTZ,
    cash_handed_over      NUMERIC(12,2),
    cash_handed_back      NUMERIC(12,2),
    note                  VARCHAR(500),
    started_by_user_id    BIGINT         NOT NULL,
    ended_by_user_id      BIGINT,

    CONSTRAINT fk_shift_relief_shift      FOREIGN KEY (shift_id)             REFERENCES shifts (id),
    CONSTRAINT fk_shift_relief_relieved   FOREIGN KEY (relieved_operator_id) REFERENCES users (id),
    CONSTRAINT fk_shift_relief_reliever   FOREIGN KEY (relief_operator_id)   REFERENCES users (id),
    CONSTRAINT fk_shift_relief_started_by FOREIGN KEY (started_by_user_id)   REFERENCES users (id),
    CONSTRAINT fk_shift_relief_ended_by   FOREIGN KEY (ended_by_user_id)     REFERENCES users (id),
    CONSTRAINT chk_shift_relief_other_operator CHECK (relieved_operator_id <> relief_operator_id),
    CONSTRAINT chk_shift_relief_ended     CHECK ((ended_at IS NULL) = (ended_by_user_id IS NULL)),
    CONSTRAINT chk_shift_relief_period    CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT chk_shift_relief_cash_not_negative CHECK (
        (cash_handed_over IS NULL OR cash_handed_over >= 0) AND (cash_handed_back IS NULL OR cash_handed_back >= 0))
);

CREATE UNIQUE INDEX uq_shift_relief_one_active_per_operator ON shift_reliefs (shift_id, relieved_operator_id)
    WHERE ended_at IS NULL;
CREATE INDEX idx_shift_relief_shift ON shift_reliefs (shift_id);

-- 4. Who worked a nozzle between which meter readings. A shift with no turns on a nozzle was worked by its operator
--    alone; the first swap or relief records that operator's turn from the opening reading.
CREATE TABLE shift_nozzle_turns (
    id                    BIGSERIAL      PRIMARY KEY,
    shift_id              BIGINT         NOT NULL,
    nozzle_id             BIGINT         NOT NULL,
    operator_id           BIGINT         NOT NULL,
    start_reading         NUMERIC(14,3)  NOT NULL,
    end_reading           NUMERIC(14,3),
    started_at            TIMESTAMPTZ    NOT NULL,
    ended_at              TIMESTAMPTZ,
    start_reason          VARCHAR(20)    NOT NULL,
    relief_id             BIGINT,
    note                  VARCHAR(500),
    recorded_by_user_id   BIGINT         NOT NULL,

    CONSTRAINT fk_nozzle_turn_shift       FOREIGN KEY (shift_id)            REFERENCES shifts (id),
    CONSTRAINT fk_nozzle_turn_nozzle      FOREIGN KEY (nozzle_id)           REFERENCES nozzles (id),
    CONSTRAINT fk_nozzle_turn_operator    FOREIGN KEY (operator_id)         REFERENCES users (id),
    CONSTRAINT fk_nozzle_turn_relief      FOREIGN KEY (relief_id)           REFERENCES shift_reliefs (id),
    CONSTRAINT fk_nozzle_turn_recorded_by FOREIGN KEY (recorded_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_nozzle_turn_reason     CHECK (start_reason IN ('SHIFT_OPEN', 'SWAP', 'RELIEF_START', 'RELIEF_END')),
    CONSTRAINT chk_nozzle_turn_ended      CHECK ((end_reading IS NULL) = (ended_at IS NULL)),
    CONSTRAINT chk_nozzle_turn_readings_not_negative CHECK (start_reading >= 0 AND (end_reading IS NULL OR end_reading >= 0))
);

CREATE UNIQUE INDEX uq_nozzle_turn_one_open ON shift_nozzle_turns (shift_id, nozzle_id) WHERE ended_at IS NULL;
CREATE INDEX idx_nozzle_turn_shift ON shift_nozzle_turns (shift_id, started_at, id);

-- 5. Each operator's share of a closed shift: the litres and amount of their turns, and what they handed in when the
--    close split the collections by operator (all collection figures or none).
CREATE TABLE shift_operator_totals (
    shift_id              BIGINT         NOT NULL,
    operator_id           BIGINT         NOT NULL,
    litres_sold           NUMERIC(14,3)  NOT NULL,
    amount_due            NUMERIC(12,2)  NOT NULL,
    cash_collected        NUMERIC(12,2),
    upi_collected         NUMERIC(12,2),
    card_collected        NUMERIC(12,2),
    fleet_card_collected  NUMERIC(12,2),
    credit_total          NUMERIC(12,2),

    CONSTRAINT pk_shift_operator_totals PRIMARY KEY (shift_id, operator_id),
    CONSTRAINT fk_operator_totals_shift    FOREIGN KEY (shift_id)    REFERENCES shifts (id),
    CONSTRAINT fk_operator_totals_operator FOREIGN KEY (operator_id) REFERENCES users (id),
    CONSTRAINT chk_operator_totals_collections_together CHECK (
        (cash_collected IS NULL AND upi_collected IS NULL AND card_collected IS NULL
            AND fleet_card_collected IS NULL AND credit_total IS NULL)
        OR (cash_collected IS NOT NULL AND upi_collected IS NOT NULL AND card_collected IS NOT NULL
            AND fleet_card_collected IS NOT NULL AND credit_total IS NOT NULL)),
    CONSTRAINT chk_operator_totals_not_negative CHECK (
        litres_sold >= 0 AND amount_due >= 0
        AND (cash_collected IS NULL OR cash_collected >= 0) AND (upi_collected IS NULL OR upi_collected >= 0)
        AND (card_collected IS NULL OR card_collected >= 0) AND (fleet_card_collected IS NULL OR fleet_card_collected >= 0)
        AND (credit_total IS NULL OR credit_total >= 0))
);

-- 6. Staff cash advances: cash out of the safe now, recovered from later payroll, in part or whole across runs.
CREATE TABLE staff_advances (
    id                  BIGSERIAL      PRIMARY KEY,
    pump_id             BIGINT         NOT NULL,
    staff_user_id       BIGINT         NOT NULL,
    amount              NUMERIC(12,2)  NOT NULL,
    advance_date        DATE           NOT NULL,
    note                VARCHAR(500),
    cash_event_id       BIGINT         NOT NULL,
    given_by_user_id    BIGINT         NOT NULL,
    status              VARCHAR(10)    NOT NULL DEFAULT 'ACTIVE',
    void_reason         VARCHAR(500),
    voided_by_user_id   BIGINT,
    voided_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_staff_advance_pump       FOREIGN KEY (pump_id)           REFERENCES pump_locations (id),
    CONSTRAINT fk_staff_advance_staff      FOREIGN KEY (staff_user_id)     REFERENCES users (id),
    CONSTRAINT fk_staff_advance_cash_event FOREIGN KEY (cash_event_id)     REFERENCES cash_events (id),
    CONSTRAINT fk_staff_advance_given_by   FOREIGN KEY (given_by_user_id)  REFERENCES users (id),
    CONSTRAINT fk_staff_advance_voided_by  FOREIGN KEY (voided_by_user_id) REFERENCES users (id),
    CONSTRAINT uq_staff_advance_cash_event UNIQUE (cash_event_id),
    CONSTRAINT chk_staff_advance_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_staff_advance_status   CHECK (status IN ('ACTIVE', 'VOIDED')),
    CONSTRAINT chk_staff_advance_voided   CHECK (
        (status = 'VOIDED') = (void_reason IS NOT NULL AND voided_by_user_id IS NOT NULL AND voided_at IS NOT NULL))
);

CREATE INDEX idx_staff_advance_pump_staff ON staff_advances (pump_id, staff_user_id, advance_date, id);

-- Deleting a draft payroll gives back what it recovered.
CREATE TABLE staff_advance_recoveries (
    id           BIGSERIAL      PRIMARY KEY,
    advance_id   BIGINT         NOT NULL,
    payroll_id   BIGINT         NOT NULL,
    amount       NUMERIC(12,2)  NOT NULL,

    CONSTRAINT fk_advance_recovery_advance FOREIGN KEY (advance_id) REFERENCES staff_advances (id),
    CONSTRAINT fk_advance_recovery_payroll FOREIGN KEY (payroll_id) REFERENCES payroll_records (id) ON DELETE CASCADE,
    CONSTRAINT uq_advance_recovery_per_payroll UNIQUE (advance_id, payroll_id),
    CONSTRAINT chk_advance_recovery_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_advance_recovery_payroll ON staff_advance_recoveries (payroll_id);

ALTER TABLE payroll_records
    ADD COLUMN advance_recovered NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_payroll_advance_recovered_not_negative CHECK (advance_recovered >= 0);

ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'BLIND_CASH_COUNTED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'SHIFT_OPERATOR_ADDED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'SHIFT_OPERATOR_REMOVED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'NOZZLE_SWAPPED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'RELIEF_STARTED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'RELIEF_ENDED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'STAFF_ADVANCE_GIVEN';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'STAFF_ADVANCE_VOIDED';
