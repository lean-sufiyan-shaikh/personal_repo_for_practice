-- Cash custody chain: cash drops from open shifts into the safe, safe counts, and bank deposits out of it.
-- The safe's ledger stays cash_events; a confirmed drop adds a CASH_DROP row and a deposit a BANK_DEPOSIT row.
ALTER TYPE cash_event_type ADD VALUE IF NOT EXISTS 'CASH_DROP';
ALTER TYPE cash_event_type ADD VALUE IF NOT EXISTS 'BANK_DEPOSIT';

-- cash_collected keeps the shift's whole cash (bag + confirmed drops) so reports are unchanged;
-- cash_dropped is the confirmed-drop part. Denominations are the bag count, NULL when only a total was typed.
ALTER TABLE shifts
    ADD COLUMN cash_dropped       NUMERIC(12,2),
    ADD COLUMN cash_denominations JSONB,
    ADD CONSTRAINT chk_shift_cash_dropped_not_negative CHECK (cash_dropped IS NULL OR cash_dropped >= 0);

CREATE TABLE cash_drops (
    id                   BIGSERIAL       PRIMARY KEY,
    pump_id              BIGINT          NOT NULL,
    shift_id             BIGINT          NOT NULL,
    operator_id          BIGINT          NOT NULL,
    amount               NUMERIC(12,2)   NOT NULL,
    denominations        JSONB,
    status               VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    recorded_by_user_id  BIGINT          NOT NULL,
    reviewed_by_user_id  BIGINT,
    reviewed_at          TIMESTAMPTZ,
    rejection_reason     VARCHAR(500),
    cash_event_id        BIGINT,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_cash_drop_pump        FOREIGN KEY (pump_id)             REFERENCES pump_locations (id),
    CONSTRAINT fk_cash_drop_shift       FOREIGN KEY (shift_id)            REFERENCES shifts (id),
    CONSTRAINT fk_cash_drop_operator    FOREIGN KEY (operator_id)         REFERENCES users (id),
    CONSTRAINT fk_cash_drop_recorded_by FOREIGN KEY (recorded_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_cash_drop_reviewed_by FOREIGN KEY (reviewed_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_cash_drop_cash_event  FOREIGN KEY (cash_event_id)       REFERENCES cash_events (id),
    CONSTRAINT uq_cash_drop_cash_event  UNIQUE (cash_event_id),
    CONSTRAINT chk_cash_drop_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_cash_drop_status     CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
    CONSTRAINT chk_cash_drop_reviewed_fields CHECK ((status = 'PENDING') = (reviewed_at IS NULL AND reviewed_by_user_id IS NULL)),
    CONSTRAINT chk_cash_drop_confirmed_fields CHECK ((status = 'CONFIRMED') = (cash_event_id IS NOT NULL)),
    CONSTRAINT chk_cash_drop_rejected_fields CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL))
);

CREATE INDEX idx_cash_drop_shift ON cash_drops (shift_id, created_at);
CREATE INDEX idx_cash_drop_pump_status ON cash_drops (pump_id, status, created_at DESC);

-- A physical count of the safe against the ledger balance at that moment.
CREATE TABLE safe_counts (
    id                   BIGSERIAL       PRIMARY KEY,
    pump_id              BIGINT          NOT NULL,
    counted_amount       NUMERIC(12,2)   NOT NULL,
    expected_amount      NUMERIC(12,2)   NOT NULL,
    variance_amount      NUMERIC(12,2)   NOT NULL,
    variance_type        VARCHAR(10),
    tolerance_amount     NUMERIC(12,2)   NOT NULL,
    denominations        JSONB,
    notes                VARCHAR(500),
    counted_by_user_id   BIGINT          NOT NULL,
    counted_at           TIMESTAMPTZ     NOT NULL,

    CONSTRAINT fk_safe_count_pump       FOREIGN KEY (pump_id)            REFERENCES pump_locations (id),
    CONSTRAINT fk_safe_count_counted_by FOREIGN KEY (counted_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_safe_count_counted_not_negative CHECK (counted_amount >= 0),
    CONSTRAINT chk_safe_count_tolerance_not_negative CHECK (tolerance_amount >= 0),
    CONSTRAINT chk_safe_count_variance CHECK (variance_amount = counted_amount - expected_amount),
    CONSTRAINT chk_safe_count_variance_type CHECK (
        (variance_type IS NULL AND variance_amount = 0)
        OR (variance_type = 'SHORT' AND variance_amount < 0)
        OR (variance_type = 'OVER' AND variance_amount > 0))
);

CREATE INDEX idx_safe_count_pump ON safe_counts (pump_id, counted_at DESC);

CREATE TABLE bank_deposits (
    id                   BIGSERIAL       PRIMARY KEY,
    pump_id              BIGINT          NOT NULL,
    amount               NUMERIC(12,2)   NOT NULL,
    bank_account         VARCHAR(100)    NOT NULL,
    deposit_date         DATE            NOT NULL,
    slip_reference       VARCHAR(100)    NOT NULL,
    notes                VARCHAR(500),
    cash_event_id        BIGINT          NOT NULL,
    recorded_by_user_id  BIGINT          NOT NULL,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_bank_deposit_pump        FOREIGN KEY (pump_id)             REFERENCES pump_locations (id),
    CONSTRAINT fk_bank_deposit_cash_event  FOREIGN KEY (cash_event_id)       REFERENCES cash_events (id),
    CONSTRAINT fk_bank_deposit_recorded_by FOREIGN KEY (recorded_by_user_id) REFERENCES users (id),
    CONSTRAINT uq_bank_deposit_cash_event  UNIQUE (cash_event_id),
    CONSTRAINT uq_bank_deposit_slip        UNIQUE (pump_id, slip_reference),
    CONSTRAINT chk_bank_deposit_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_bank_deposit_slip_not_blank CHECK (btrim(slip_reference) <> ''),
    CONSTRAINT chk_bank_deposit_account_not_blank CHECK (btrim(bank_account) <> '')
);

CREATE INDEX idx_bank_deposit_pump_date ON bank_deposits (pump_id, deposit_date DESC, created_at DESC);

-- The optional slip photo lives in its own row so listing deposits never reads the file.
CREATE TABLE bank_deposit_slips (
    bank_deposit_id      BIGINT          PRIMARY KEY,
    content_type         VARCHAR(50)     NOT NULL,
    file_name            VARCHAR(255)    NOT NULL,
    size_bytes           INTEGER         NOT NULL,
    content              BYTEA           NOT NULL,
    uploaded_by_user_id  BIGINT          NOT NULL,
    uploaded_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_deposit_slip_deposit     FOREIGN KEY (bank_deposit_id)     REFERENCES bank_deposits (id) ON DELETE CASCADE,
    CONSTRAINT fk_deposit_slip_uploaded_by FOREIGN KEY (uploaded_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_deposit_slip_type CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    CONSTRAINT chk_deposit_slip_size CHECK (size_bytes > 0 AND size_bytes <= 5242880)
);

-- A safe count further from the ledger than this notifies the owner.
ALTER TABLE pump_locations
    ADD COLUMN safe_count_tolerance NUMERIC(12,2) NOT NULL DEFAULT 10.00,
    ADD CONSTRAINT chk_pump_safe_count_tolerance_range CHECK (safe_count_tolerance >= 0 AND safe_count_tolerance <= 10000);

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'CASH_DROP_PENDING';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'SAFE_COUNT_VARIANCE';

ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CASH_DROP_RECORDED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CASH_DROP_CONFIRMED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CASH_DROP_REJECTED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'SAFE_COUNTED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'BANK_DEPOSIT_RECORDED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'DEPOSIT_SLIP_ATTACHED';
