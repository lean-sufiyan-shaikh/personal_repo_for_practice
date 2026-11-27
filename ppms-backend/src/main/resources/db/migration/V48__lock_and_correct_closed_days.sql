-- Locking and correcting closed days: closed shifts can be amended (with a history row each time), manual safe
-- movements are reversed instead of edited, and supplier payments can be voided and carry a cash movement.

-- One row per amendment of a closed shift: the figures before and after and why it was amended.
CREATE TABLE shift_amendments (
    id                   BIGSERIAL      PRIMARY KEY,
    pump_id              BIGINT         NOT NULL,
    shift_id             BIGINT         NOT NULL,
    reason               VARCHAR(500)   NOT NULL,
    before_figures       JSONB          NOT NULL,
    after_figures        JSONB          NOT NULL,
    amended_by_user_id   BIGINT         NOT NULL,
    amended_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_shift_amendment_pump       FOREIGN KEY (pump_id)            REFERENCES pump_locations (id),
    CONSTRAINT fk_shift_amendment_shift      FOREIGN KEY (shift_id)           REFERENCES shifts (id),
    CONSTRAINT fk_shift_amendment_amended_by FOREIGN KEY (amended_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_shift_amendment_reason    CHECK (btrim(reason) <> '')
);

CREATE INDEX idx_shift_amendment_shift ON shift_amendments (shift_id, amended_at DESC);

-- The safe's ledger: which shift a movement came from, whether a person typed it, and reversal links.
ALTER TABLE cash_events
    ADD COLUMN shift_id           BIGINT,
    ADD COLUMN recorded_by_hand   BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN reverses_event_id  BIGINT,
    ADD COLUMN reversed_at        TIMESTAMPTZ,
    ADD CONSTRAINT fk_cash_event_shift    FOREIGN KEY (shift_id)          REFERENCES shifts (id),
    ADD CONSTRAINT fk_cash_event_reverses FOREIGN KEY (reverses_event_id) REFERENCES cash_events (id),
    ADD CONSTRAINT uq_cash_event_reverses UNIQUE (reverses_event_id),
    ADD CONSTRAINT chk_cash_event_reversal_not_reversed CHECK (reverses_event_id IS NULL OR reversed_at IS NULL);

UPDATE cash_events e
SET shift_id = s.id
FROM shifts s
WHERE e.event_type = 'CASH_IN'
  AND e.description ~ 'shift close \(Shift #[0-9]+\)$'
  AND s.id = substring(e.description FROM 'shift close \(Shift #([0-9]+)\)$')::BIGINT
  AND s.pump_id = e.pump_id;

UPDATE cash_events
SET recorded_by_hand = TRUE
WHERE event_type IN ('OPENING_BALANCE', 'CLOSING_BALANCE', 'CASH_OUT')
   OR (event_type = 'CASH_IN'
       AND shift_id IS NULL
       AND description NOT LIKE 'Cash recovered from operator for the shortage on Shift #%'
       AND description NOT LIKE '% (Sale #%)');

CREATE INDEX idx_cash_event_shift ON cash_events (shift_id) WHERE shift_id IS NOT NULL;

-- One live opening balance per pump. Pumps that already hold several keep them; the index then covers new rows only.
DO $$
DECLARE
    last_existing_id BIGINT;
BEGIN
    IF EXISTS (SELECT 1 FROM cash_events WHERE event_type = 'OPENING_BALANCE'
               GROUP BY pump_id HAVING COUNT(*) > 1) THEN
        SELECT COALESCE(MAX(id), 0) INTO last_existing_id FROM cash_events;
        EXECUTE format('CREATE UNIQUE INDEX uq_cash_event_one_opening_balance ON cash_events (pump_id) '
                       'WHERE event_type = ''OPENING_BALANCE'' AND reversed_at IS NULL AND id > %s', last_existing_id);
    ELSE
        CREATE UNIQUE INDEX uq_cash_event_one_opening_balance ON cash_events (pump_id)
            WHERE event_type = 'OPENING_BALANCE' AND reversed_at IS NULL;
    END IF;
END $$;

-- Supplier payments: voided with a reason instead of deleted, and a cash payment's movement out of the safe.
ALTER TABLE fuel_supplier_payments
    ADD COLUMN void_status        VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN void_reason        VARCHAR(500),
    ADD COLUMN voided_by_user_id  BIGINT,
    ADD COLUMN voided_at          TIMESTAMPTZ,
    ADD COLUMN cash_event_id      BIGINT,
    ADD CONSTRAINT fk_fsp_voided_by   FOREIGN KEY (voided_by_user_id) REFERENCES users (id),
    ADD CONSTRAINT fk_fsp_cash_event  FOREIGN KEY (cash_event_id)     REFERENCES cash_events (id),
    ADD CONSTRAINT chk_fsp_void_status CHECK (void_status IN ('ACTIVE', 'VOIDED')),
    ADD CONSTRAINT chk_fsp_void_fields CHECK ((void_status = 'VOIDED') =
        (voided_at IS NOT NULL AND voided_by_user_id IS NOT NULL AND void_reason IS NOT NULL AND btrim(void_reason) <> ''));

-- A reference is used once per supplier among live payments. Older duplicates are kept; the index then covers new rows.
DO $$
DECLARE
    last_existing_id BIGINT;
BEGIN
    IF EXISTS (SELECT 1 FROM fuel_supplier_payments WHERE reference_no IS NOT NULL AND btrim(reference_no) <> ''
               GROUP BY supplier_id, lower(btrim(reference_no)) HAVING COUNT(*) > 1) THEN
        SELECT COALESCE(MAX(id), 0) INTO last_existing_id FROM fuel_supplier_payments;
        EXECUTE format('CREATE UNIQUE INDEX uq_fsp_supplier_reference ON fuel_supplier_payments '
                       '(supplier_id, lower(btrim(reference_no))) '
                       'WHERE reference_no IS NOT NULL AND void_status = ''ACTIVE'' AND id > %s', last_existing_id);
    ELSE
        CREATE UNIQUE INDEX uq_fsp_supplier_reference ON fuel_supplier_payments (supplier_id, lower(btrim(reference_no)))
            WHERE reference_no IS NOT NULL AND void_status = 'ACTIVE';
    END IF;
END $$;

ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'SHIFT_AMENDED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CASH_EVENT_RECORDED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CASH_EVENT_REVERSED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'SUPPLIER_PAYMENT_VOIDED';
