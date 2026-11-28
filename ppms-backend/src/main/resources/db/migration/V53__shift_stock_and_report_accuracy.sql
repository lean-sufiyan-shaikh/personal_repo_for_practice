-- Shift, stock and report accuracy: price segments for a price change while shifts are open, early-start grace and
-- rounding tolerance per pump, a default discrepancy escalation threshold, one open shift per operator, the real close
-- time, stock shortfalls at close, partial cash recovery and the history of investigation notes.

-- 1. Pump settings. A pump without an escalation threshold now escalates discrepancies above Rs 500.
ALTER TABLE pump_locations
    ADD COLUMN early_start_grace_minutes INTEGER       NOT NULL DEFAULT 30,
    ADD COLUMN rounding_tolerance        NUMERIC(6,2)  NOT NULL DEFAULT 1.00,
    ADD CONSTRAINT chk_pump_early_start_grace_range CHECK (early_start_grace_minutes BETWEEN 0 AND 120),
    ADD CONSTRAINT chk_pump_rounding_tolerance_range CHECK (rounding_tolerance >= 0 AND rounding_tolerance <= 100);

UPDATE pump_locations SET discrepancy_escalation_threshold = 500.00 WHERE discrepancy_escalation_threshold IS NULL;

ALTER TABLE pump_locations
    ALTER COLUMN discrepancy_escalation_threshold SET DEFAULT 500.00,
    ALTER COLUMN discrepancy_escalation_threshold SET NOT NULL,
    ADD CONSTRAINT chk_pump_escalation_threshold_not_negative CHECK (discrepancy_escalation_threshold >= 0);

UPDATE shifts s
SET status = CASE WHEN s.discrepancy_amount > p.discrepancy_escalation_threshold
                      THEN 'CLOSED_DISCREPANCY_PENDING_APPROVAL'::shift_status
                  ELSE 'CLOSED_DISCREPANCY_PENDING'::shift_status
             END
FROM pump_locations p
WHERE p.id = s.pump_id
  AND s.status IN ('CLOSED_DISCREPANCY_PENDING', 'CLOSED_DISCREPANCY_PENDING_APPROVAL')
  AND s.discrepancy_amount IS NOT NULL;

-- 2. Shift close facts: when it really closed, a difference absorbed as rounding, a stock shortfall at close and
--    the day a (possibly partial) cash recovery was received.
ALTER TABLE shifts
    ADD COLUMN closed_at            TIMESTAMPTZ,
    ADD COLUMN rounding_adjustment  NUMERIC(12,2),
    ADD COLUMN stock_shortfall      BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN cash_recovered_at    TIMESTAMPTZ;

UPDATE shifts SET cash_recovery_amount = NULL WHERE cash_recovery_amount IS NOT NULL AND cash_recovery_amount <= 0;
UPDATE shifts
SET cash_recovered_at = COALESCE(discrepancy_resolved_at, updated_at)
WHERE cash_recovery_amount IS NOT NULL;

-- Added after the backfill above, so shifts that already recorded a recovery satisfy them.
ALTER TABLE shifts
    ADD CONSTRAINT chk_shift_cash_recovery_positive CHECK (cash_recovery_amount IS NULL OR cash_recovery_amount > 0),
    ADD CONSTRAINT chk_shift_cash_recovery_fields CHECK ((cash_recovery_amount IS NULL) = (cash_recovered_at IS NULL)),
    ADD CONSTRAINT chk_shift_cash_recovery_within_discrepancy
        CHECK (cash_recovery_amount IS NULL OR cash_recovery_amount <= discrepancy_amount);

-- 3. Litres a shift sold that the tank's book stock could not cover at close; cleared as later deliveries cover them.
ALTER TABLE shift_fuel_readings
    ADD COLUMN stock_shortfall_litres NUMERIC(14,3) NOT NULL DEFAULT 0,
    ADD COLUMN sales_amount           NUMERIC(16,5),
    ADD CONSTRAINT chk_sfr_stock_shortfall_not_negative CHECK (stock_shortfall_litres >= 0);

UPDATE shift_fuel_readings
SET sales_amount = units_sold * price_snapshot
WHERE units_sold IS NOT NULL;

CREATE INDEX idx_sfr_open_stock_shortfall ON shift_fuel_readings (tank_id) WHERE stock_shortfall_litres > 0;

-- 4. Price segments: a price change while a shift is open splits each open nozzle's litres at the meter reading
--    taken at the change. Segment 0 is the opening price; units are filled in at close.
CREATE TABLE shift_reading_price_segments (
    id                     BIGSERIAL      PRIMARY KEY,
    shift_fuel_reading_id  BIGINT         NOT NULL,
    start_reading          NUMERIC(14,3)  NOT NULL,
    price_per_unit         NUMERIC(10,3)  NOT NULL,
    starts_at              TIMESTAMPTZ    NOT NULL,
    units_sold             NUMERIC(14,3),
    recorded_by_user_id    BIGINT,

    CONSTRAINT fk_price_segment_reading     FOREIGN KEY (shift_fuel_reading_id) REFERENCES shift_fuel_readings (id) ON DELETE CASCADE,
    CONSTRAINT fk_price_segment_recorded_by FOREIGN KEY (recorded_by_user_id)   REFERENCES users (id),
    CONSTRAINT chk_price_segment_price_positive   CHECK (price_per_unit > 0),
    CONSTRAINT chk_price_segment_reading_not_negative CHECK (start_reading >= 0),
    CONSTRAINT chk_price_segment_units_not_negative   CHECK (units_sold IS NULL OR units_sold >= 0)
);

CREATE INDEX idx_price_segment_reading ON shift_reading_price_segments (shift_fuel_reading_id, starts_at, id);

-- 5. Every investigation note is kept; shifts.investigation_* keep the latest one.
CREATE TABLE shift_investigation_notes (
    id                  BIGSERIAL      PRIMARY KEY,
    shift_id            BIGINT         NOT NULL,
    note                TEXT           NOT NULL,
    written_by_user_id  BIGINT         NOT NULL,
    written_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_investigation_note_shift     FOREIGN KEY (shift_id)           REFERENCES shifts (id) ON DELETE CASCADE,
    CONSTRAINT fk_investigation_note_writer    FOREIGN KEY (written_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_investigation_note_not_blank CHECK (btrim(note) <> '')
);

CREATE INDEX idx_investigation_note_shift ON shift_investigation_notes (shift_id, written_at, id);

INSERT INTO shift_investigation_notes (shift_id, note, written_by_user_id, written_at)
SELECT id, investigation_note, investigation_started_by_id, investigation_started_at
FROM shifts
WHERE investigation_note IS NOT NULL AND btrim(investigation_note) <> ''
  AND investigation_started_by_id IS NOT NULL AND investigation_started_at IS NOT NULL;

-- 6. One open shift per operator. Operators that already hold several keep them; the index then covers new rows only.
DO $$
DECLARE
    last_existing_id BIGINT;
BEGIN
    IF EXISTS (SELECT 1 FROM shifts WHERE status IN ('OPEN', 'OPEN_OVERDUE')
               GROUP BY operator_id HAVING COUNT(*) > 1) THEN
        SELECT COALESCE(MAX(id), 0) INTO last_existing_id FROM shifts;
        EXECUTE format('CREATE UNIQUE INDEX uq_shift_one_open_per_operator ON shifts (operator_id) '
                       'WHERE status IN (''OPEN'', ''OPEN_OVERDUE'') AND id > %s', last_existing_id);
    ELSE
        CREATE UNIQUE INDEX uq_shift_one_open_per_operator ON shifts (operator_id)
            WHERE status IN ('OPEN', 'OPEN_OVERDUE');
    END IF;
END $$;
