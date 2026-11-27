-- Money operations: where an expense was paid from and voiding it, the petty cash box, chargebacks and refunds
-- against card and UPI collections, and closing a month.

-- 1. Expenses: the source of the money, the cash movement it made and voiding. Expenses recorded before V57 have no
--    source and moved no cash.
ALTER TYPE expense_approval_status ADD VALUE IF NOT EXISTS 'VOIDED';

ALTER TABLE pump_expenses
    ADD COLUMN paid_from          VARCHAR(20),
    ADD COLUMN cash_event_id      BIGINT,
    ADD COLUMN void_reason        VARCHAR(500),
    ADD COLUMN voided_by_user_id  BIGINT,
    ADD COLUMN voided_at          TIMESTAMPTZ,
    ADD CONSTRAINT fk_expense_cash_event FOREIGN KEY (cash_event_id)     REFERENCES cash_events (id),
    ADD CONSTRAINT fk_expense_voided_by  FOREIGN KEY (voided_by_user_id) REFERENCES users (id),
    ADD CONSTRAINT uq_expense_cash_event UNIQUE (cash_event_id),
    ADD CONSTRAINT chk_expense_paid_from CHECK (paid_from IS NULL
        OR paid_from IN ('SAFE', 'PETTY_CASH', 'BANK', 'OWNER_POCKET')),
    ADD CONSTRAINT chk_expense_void_fields CHECK ((voided_at IS NULL) = (voided_by_user_id IS NULL)
        AND (voided_at IS NULL) = (void_reason IS NULL OR btrim(void_reason) = ''));

-- 2. Petty cash: one box per pump with its imprest float, a ledger of signed movements (money in is positive) and counts.
CREATE TABLE petty_cash_boxes (
    pump_id              BIGINT          PRIMARY KEY,
    float_amount         NUMERIC(12, 2)  NOT NULL DEFAULT 0,
    count_tolerance      NUMERIC(12, 2)  NOT NULL DEFAULT 10,
    updated_by_user_id   BIGINT,
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_petty_cash_box_pump       FOREIGN KEY (pump_id)            REFERENCES pump_locations (id),
    CONSTRAINT fk_petty_cash_box_updated_by FOREIGN KEY (updated_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_petty_cash_float         CHECK (float_amount >= 0 AND float_amount <= 1000000),
    CONSTRAINT chk_petty_cash_tolerance     CHECK (count_tolerance >= 0 AND count_tolerance <= 10000)
);

CREATE TABLE petty_cash_entries (
    id                   BIGSERIAL       PRIMARY KEY,
    pump_id              BIGINT          NOT NULL,
    entry_type           VARCHAR(20)     NOT NULL,
    amount               NUMERIC(14, 2)  NOT NULL,
    entry_date           DATE            NOT NULL,
    description          VARCHAR(500)    NOT NULL,
    expense_id           BIGINT,
    safe_cash_event_id   BIGINT,
    reverses_entry_id    BIGINT,
    reversed_at          TIMESTAMPTZ,
    recorded_by_user_id  BIGINT          NOT NULL,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_petty_cash_entry_pump        FOREIGN KEY (pump_id)             REFERENCES pump_locations (id),
    CONSTRAINT fk_petty_cash_entry_expense     FOREIGN KEY (expense_id)          REFERENCES pump_expenses (id),
    CONSTRAINT fk_petty_cash_entry_safe_event  FOREIGN KEY (safe_cash_event_id)  REFERENCES cash_events (id),
    CONSTRAINT fk_petty_cash_entry_reverses    FOREIGN KEY (reverses_entry_id)   REFERENCES petty_cash_entries (id),
    CONSTRAINT fk_petty_cash_entry_recorded_by FOREIGN KEY (recorded_by_user_id) REFERENCES users (id),
    CONSTRAINT uq_petty_cash_entry_reverses    UNIQUE (reverses_entry_id),
    CONSTRAINT uq_petty_cash_entry_safe_event  UNIQUE (safe_cash_event_id),
    CONSTRAINT chk_petty_cash_entry_type       CHECK (entry_type IN ('TOP_UP', 'EXPENSE', 'REVERSAL')),
    CONSTRAINT chk_petty_cash_entry_sign       CHECK ((entry_type = 'TOP_UP' AND amount > 0)
        OR (entry_type = 'EXPENSE' AND amount < 0) OR (entry_type = 'REVERSAL' AND amount <> 0)),
    CONSTRAINT chk_petty_cash_top_up_from_safe CHECK ((entry_type = 'TOP_UP') = (safe_cash_event_id IS NOT NULL)),
    CONSTRAINT chk_petty_cash_expense_linked   CHECK (entry_type <> 'EXPENSE' OR expense_id IS NOT NULL),
    CONSTRAINT chk_petty_cash_reversal_linked  CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL)),
    CONSTRAINT chk_petty_cash_reversal_not_reversed CHECK (reverses_entry_id IS NULL OR reversed_at IS NULL)
);

CREATE INDEX idx_petty_cash_entry_pump_date ON petty_cash_entries (pump_id, entry_date, created_at, id);
CREATE UNIQUE INDEX uq_petty_cash_entry_one_draw_per_expense
    ON petty_cash_entries (expense_id) WHERE entry_type = 'EXPENSE';

CREATE TABLE petty_cash_counts (
    id                   BIGSERIAL       PRIMARY KEY,
    pump_id              BIGINT          NOT NULL,
    count_for_date       DATE            NOT NULL,
    counted_amount       NUMERIC(14, 2)  NOT NULL,
    expected_amount      NUMERIC(14, 2)  NOT NULL,
    variance_amount      NUMERIC(14, 2)  NOT NULL,
    tolerance_amount     NUMERIC(12, 2)  NOT NULL,
    notes                VARCHAR(500),
    counted_by_user_id   BIGINT          NOT NULL,
    counted_at           TIMESTAMPTZ     NOT NULL,

    CONSTRAINT fk_petty_cash_count_pump       FOREIGN KEY (pump_id)            REFERENCES pump_locations (id),
    CONSTRAINT fk_petty_cash_count_counted_by FOREIGN KEY (counted_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_petty_cash_count_amount    CHECK (counted_amount >= 0),
    CONSTRAINT chk_petty_cash_count_variance  CHECK (variance_amount = counted_amount - expected_amount)
);

CREATE INDEX idx_petty_cash_count_pump_date ON petty_cash_counts (pump_id, count_for_date DESC, counted_at DESC);

-- 3. Chargebacks and refunds: money the bank took back from card, UPI or fleet card collections. They lower what
--    reached the bank without touching the gross a settlement cleared, so wallets and coverage stay as they were.
CREATE TABLE settlement_chargebacks (
    id                   BIGSERIAL       PRIMARY KEY,
    pump_id              BIGINT          NOT NULL,
    kind                 VARCHAR(12)     NOT NULL,
    payment_type         VARCHAR(12)     NOT NULL,
    chargeback_date      DATE            NOT NULL,
    amount               NUMERIC(14, 2)  NOT NULL,
    reference            VARCHAR(100),
    reason               VARCHAR(500)    NOT NULL,
    settlement_id        BIGINT,
    collection_date      DATE,
    recorded_by_user_id  BIGINT          NOT NULL,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_chargeback_pump        FOREIGN KEY (pump_id)             REFERENCES pump_locations (id),
    CONSTRAINT fk_chargeback_settlement  FOREIGN KEY (settlement_id)       REFERENCES payment_settlements (id),
    CONSTRAINT fk_chargeback_recorded_by FOREIGN KEY (recorded_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_chargeback_kind       CHECK (kind IN ('CHARGEBACK', 'REFUND')),
    CONSTRAINT chk_chargeback_payment_type CHECK (payment_type IN ('UPI', 'CARD', 'FLEET_CARD')),
    CONSTRAINT chk_chargeback_amount     CHECK (amount > 0),
    CONSTRAINT chk_chargeback_reason     CHECK (btrim(reason) <> ''),
    CONSTRAINT chk_chargeback_linked     CHECK (settlement_id IS NOT NULL OR collection_date IS NOT NULL)
);

CREATE INDEX idx_chargeback_pump_date ON settlement_chargebacks (pump_id, chargeback_date);
CREATE INDEX idx_chargeback_settlement ON settlement_chargebacks (settlement_id) WHERE settlement_id IS NOT NULL;
CREATE UNIQUE INDEX uq_chargeback_pump_reference
    ON settlement_chargebacks (pump_id, lower(btrim(reference))) WHERE reference IS NOT NULL;

-- A chargeback is a debit line on the statement; a line links to one record at most and a chargeback to one line.
ALTER TABLE bank_statement_lines
    ADD COLUMN matched_chargeback_id BIGINT,
    ADD CONSTRAINT fk_bsl_chargeback FOREIGN KEY (matched_chargeback_id) REFERENCES settlement_chargebacks (id),
    DROP CONSTRAINT chk_bsl_one_link,
    DROP CONSTRAINT chk_bsl_suggestion_has_link,
    DROP CONSTRAINT chk_bsl_unlinked_when_open;

ALTER TABLE bank_statement_lines
    ADD CONSTRAINT chk_bsl_one_link CHECK (num_nonnulls(matched_shift_id, matched_ancillary_sale_id, matched_payment_id,
        matched_settlement_id, matched_deposit_id, matched_supplier_payment_id, matched_chargeback_id) <= 1),
    ADD CONSTRAINT chk_bsl_suggestion_has_link CHECK (match_status::text <> 'SUGGESTED'
        OR num_nonnulls(matched_shift_id, matched_ancillary_sale_id, matched_payment_id, matched_settlement_id,
            matched_deposit_id, matched_supplier_payment_id, matched_chargeback_id) = 1),
    ADD CONSTRAINT chk_bsl_unlinked_when_open CHECK (match_status::text NOT IN ('UNMATCHED', 'IGNORED')
        OR num_nonnulls(matched_shift_id, matched_ancillary_sale_id, matched_payment_id, matched_settlement_id,
            matched_deposit_id, matched_supplier_payment_id, matched_chargeback_id) = 0);

CREATE UNIQUE INDEX uq_bank_statement_lines_matched_chargeback
    ON bank_statement_lines (matched_chargeback_id) WHERE matched_chargeback_id IS NOT NULL;

-- Chargebacks are a cost of the day the bank took them; stored with DAY sheets like bank charges.
ALTER TABLE balance_sheets ADD COLUMN total_chargebacks_amount NUMERIC(14, 2);

-- 4. Month close: closed once every business day of it is closed and approved or listed as an exception. Each close
--    stores an immutable summary as a new version; re-opening keeps the earlier versions.
CREATE TABLE month_closes (
    id                   BIGSERIAL       PRIMARY KEY,
    pump_id              BIGINT          NOT NULL,
    month_start          DATE            NOT NULL,
    status               VARCHAR(10)     NOT NULL,
    current_version      INTEGER         NOT NULL,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_month_close_pump        FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT uq_month_close_pump_month  UNIQUE (pump_id, month_start),
    CONSTRAINT chk_month_close_first_day  CHECK (EXTRACT(DAY FROM month_start) = 1),
    CONSTRAINT chk_month_close_status     CHECK (status IN ('CLOSED', 'REOPENED')),
    CONSTRAINT chk_month_close_version    CHECK (current_version > 0)
);

-- A version is re-opened at most once, with who did it and why; its summary never changes.
CREATE TABLE month_close_versions (
    id                   BIGSERIAL       PRIMARY KEY,
    month_close_id       BIGINT          NOT NULL,
    version              INTEGER         NOT NULL,
    summary              JSONB           NOT NULL,
    closed_by_user_id    BIGINT          NOT NULL,
    closed_at            TIMESTAMPTZ     NOT NULL,
    reopened_by_user_id  BIGINT,
    reopened_at          TIMESTAMPTZ,
    reopen_reason        VARCHAR(500),

    CONSTRAINT fk_month_close_version_close       FOREIGN KEY (month_close_id)      REFERENCES month_closes (id),
    CONSTRAINT fk_month_close_version_closed_by   FOREIGN KEY (closed_by_user_id)   REFERENCES users (id),
    CONSTRAINT fk_month_close_version_reopened_by FOREIGN KEY (reopened_by_user_id) REFERENCES users (id),
    CONSTRAINT uq_month_close_version             UNIQUE (month_close_id, version),
    CONSTRAINT chk_month_close_version_number     CHECK (version > 0),
    CONSTRAINT chk_month_close_version_reopen     CHECK ((reopened_at IS NULL) = (reopened_by_user_id IS NULL)
        AND (reopened_at IS NULL) = (reopen_reason IS NULL OR btrim(reopen_reason) = ''))
);

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'PETTY_CASH_COUNT_VARIANCE';

ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'EXPENSE_VOIDED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'PETTY_CASH_SETTINGS_UPDATED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'PETTY_CASH_TOPPED_UP';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'PETTY_CASH_COUNTED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CHARGEBACK_RECORDED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'MONTH_CLOSED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'MONTH_REOPENED';
