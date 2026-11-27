-- Credit desk workflows: credit notes and GST credit notes, advances and their refunds, security deposits, bad-debt
-- write-off, closing and merging accounts, disputed debits, group invoices. New enum values cannot be used in the
-- transaction that adds them, so no statement below names them.

-- 1. Account life cycle: closed (keeping its history), merged into another account, written off.
ALTER TABLE credit_clients
    ADD COLUMN closed_at              TIMESTAMPTZ,
    ADD COLUMN closed_by              BIGINT,
    ADD COLUMN close_reason           VARCHAR(500),
    ADD COLUMN merged_into_client_id  BIGINT,
    ADD COLUMN written_off_on         DATE;

ALTER TABLE credit_clients
    ADD CONSTRAINT fk_credit_client_closed_by    FOREIGN KEY (closed_by)             REFERENCES users (id),
    ADD CONSTRAINT fk_credit_client_merged_into  FOREIGN KEY (merged_into_client_id) REFERENCES credit_clients (id),
    ADD CONSTRAINT chk_credit_client_closed      CHECK ((closed_at IS NULL) = (closed_by IS NULL)
                                                        AND (closed_at IS NULL) = (close_reason IS NULL)),
    ADD CONSTRAINT chk_credit_client_merged      CHECK (merged_into_client_id IS NULL
                                                        OR (closed_at IS NOT NULL AND merged_into_client_id <> id));

-- 2. Credit-side and refund rows of an account. CREDIT_NOTE, WRITE_OFF and DEPOSIT_APPLIED lower what the account
-- owes; ADVANCE_REFUND pays back part of an advance, so it raises it. A credit note may name the debit it credits.
CREATE TABLE credit_disputes (
    id               BIGSERIAL       PRIMARY KEY,
    pump_id          BIGINT          NOT NULL,
    client_id        BIGINT          NOT NULL,
    debit_type       VARCHAR(20)     NOT NULL,
    debit_id         BIGINT          NOT NULL,
    debit_date       DATE            NOT NULL,
    amount           NUMERIC(14,2)   NOT NULL,
    reason           VARCHAR(500)    NOT NULL,
    status           VARCHAR(10)     NOT NULL DEFAULT 'OPEN',
    raised_by        BIGINT          NOT NULL,
    raised_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    resolved_by      BIGINT,
    resolved_at      TIMESTAMPTZ,
    resolution_note  VARCHAR(500),

    CONSTRAINT fk_credit_dispute_pump        FOREIGN KEY (pump_id)     REFERENCES pump_locations (id),
    CONSTRAINT fk_credit_dispute_client      FOREIGN KEY (client_id)   REFERENCES credit_clients (id),
    CONSTRAINT fk_credit_dispute_raised_by   FOREIGN KEY (raised_by)   REFERENCES users (id),
    CONSTRAINT fk_credit_dispute_resolved_by FOREIGN KEY (resolved_by) REFERENCES users (id),
    CONSTRAINT chk_credit_dispute_debit_type CHECK (debit_type IN ('SALE', 'COUNTER_SALE', 'INTEREST', 'BOUNCE_CHARGE')),
    CONSTRAINT chk_credit_dispute_status     CHECK (status IN ('OPEN', 'ACCEPTED', 'CREDITED')),
    CONSTRAINT chk_credit_dispute_amount     CHECK (amount > 0),
    CONSTRAINT chk_credit_dispute_reason     CHECK (length(trim(reason)) > 0),
    CONSTRAINT chk_credit_dispute_resolved   CHECK ((status = 'OPEN') = (resolved_at IS NULL)
                                                    AND (resolved_at IS NULL) = (resolved_by IS NULL))
);

CREATE UNIQUE INDEX uq_credit_dispute_open_debit ON credit_disputes (debit_type, debit_id) WHERE status = 'OPEN';
CREATE INDEX idx_credit_dispute_client ON credit_disputes (client_id);

CREATE TABLE credit_adjustments (
    id                 BIGSERIAL       PRIMARY KEY,
    pump_id            BIGINT          NOT NULL,
    client_id          BIGINT          NOT NULL,
    kind               VARCHAR(20)     NOT NULL,
    amount             NUMERIC(14,2)   NOT NULL,
    adjustment_date    DATE            NOT NULL,
    reason             VARCHAR(500)    NOT NULL,
    debit_type         VARCHAR(20),
    debit_id           BIGINT,
    debit_date         DATE,
    returned_quantity  NUMERIC(12,3),
    dispute_id         BIGINT,
    refund_mode        VARCHAR(20),
    refund_reference   VARCHAR(100),
    cash_event_id      BIGINT,
    created_by         BIGINT          NOT NULL,
    created_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_credit_adjustment_pump       FOREIGN KEY (pump_id)       REFERENCES pump_locations (id),
    CONSTRAINT fk_credit_adjustment_client     FOREIGN KEY (client_id)     REFERENCES credit_clients (id),
    CONSTRAINT fk_credit_adjustment_dispute    FOREIGN KEY (dispute_id)    REFERENCES credit_disputes (id),
    CONSTRAINT fk_credit_adjustment_cash_event FOREIGN KEY (cash_event_id) REFERENCES cash_events (id),
    CONSTRAINT fk_credit_adjustment_created_by FOREIGN KEY (created_by)    REFERENCES users (id),
    CONSTRAINT chk_credit_adjustment_kind      CHECK (kind IN ('CREDIT_NOTE', 'WRITE_OFF', 'DEPOSIT_APPLIED', 'ADVANCE_REFUND')),
    CONSTRAINT chk_credit_adjustment_amount    CHECK (amount > 0),
    CONSTRAINT chk_credit_adjustment_reason    CHECK (length(trim(reason)) > 0),
    CONSTRAINT chk_credit_adjustment_debit     CHECK ((debit_type IS NULL) = (debit_id IS NULL)
                                                      AND (debit_id IS NULL) = (debit_date IS NULL)
                                                      AND (debit_type IS NULL OR kind = 'CREDIT_NOTE')),
    CONSTRAINT chk_credit_adjustment_debit_type CHECK (debit_type IN ('SALE', 'COUNTER_SALE', 'INTEREST', 'BOUNCE_CHARGE')),
    CONSTRAINT chk_credit_adjustment_quantity  CHECK (returned_quantity IS NULL OR returned_quantity > 0),
    CONSTRAINT chk_credit_adjustment_refund    CHECK ((refund_mode IS NOT NULL) = (kind = 'ADVANCE_REFUND')),
    CONSTRAINT chk_credit_adjustment_refund_mode CHECK (refund_mode IN ('CASH', 'UPI', 'BANK_TRANSFER', 'CHEQUE', 'OTHER'))
);

CREATE INDEX idx_credit_adjustment_client    ON credit_adjustments (client_id);
CREATE INDEX idx_credit_adjustment_pump_kind ON credit_adjustments (pump_id, kind, adjustment_date);
CREATE INDEX idx_credit_adjustment_debit     ON credit_adjustments (debit_type, debit_id) WHERE debit_id IS NOT NULL;

-- 3. Security deposits: held apart from the balance until an owner applies part of one to the dues or refunds it.
CREATE TABLE credit_deposits (
    id              BIGSERIAL       PRIMARY KEY,
    pump_id         BIGINT          NOT NULL,
    client_id       BIGINT          NOT NULL,
    kind            VARCHAR(10)     NOT NULL,
    amount          NUMERIC(14,2)   NOT NULL,
    movement_date   DATE            NOT NULL,
    payment_mode    VARCHAR(20),
    reference_no    VARCHAR(100),
    reason          VARCHAR(500),
    adjustment_id   BIGINT,
    cash_event_id   BIGINT,
    created_by      BIGINT          NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_credit_deposit_pump       FOREIGN KEY (pump_id)       REFERENCES pump_locations (id),
    CONSTRAINT fk_credit_deposit_client     FOREIGN KEY (client_id)     REFERENCES credit_clients (id),
    CONSTRAINT fk_credit_deposit_adjustment FOREIGN KEY (adjustment_id) REFERENCES credit_adjustments (id),
    CONSTRAINT fk_credit_deposit_cash_event FOREIGN KEY (cash_event_id) REFERENCES cash_events (id),
    CONSTRAINT fk_credit_deposit_created_by FOREIGN KEY (created_by)    REFERENCES users (id),
    CONSTRAINT chk_credit_deposit_kind      CHECK (kind IN ('RECEIVED', 'APPLIED', 'REFUNDED')),
    CONSTRAINT chk_credit_deposit_amount    CHECK (amount > 0),
    CONSTRAINT chk_credit_deposit_applied   CHECK ((kind = 'APPLIED') = (adjustment_id IS NOT NULL)),
    CONSTRAINT chk_credit_deposit_mode      CHECK ((kind = 'APPLIED') = (payment_mode IS NULL)
                                                   AND payment_mode IN ('CASH', 'UPI', 'BANK_TRANSFER', 'CHEQUE', 'OTHER')),
    CONSTRAINT chk_credit_deposit_reason    CHECK (kind = 'RECEIVED' OR length(trim(reason)) > 0)
);

CREATE INDEX idx_credit_deposit_client ON credit_deposits (client_id);

-- 4. GST credit notes: their own gapless series per pump and financial year, each crediting one line of an issued
-- invoice. Seller and buyer are read from that invoice, whose values are a snapshot.
CREATE TABLE credit_note_sequences (
    pump_id         BIGINT      NOT NULL,
    financial_year  VARCHAR(7)  NOT NULL,
    next_number     INTEGER     NOT NULL,

    CONSTRAINT pk_credit_note_sequences PRIMARY KEY (pump_id, financial_year),
    CONSTRAINT fk_cns_pump FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT chk_cns_next_number CHECK (next_number >= 1)
);

CREATE TABLE gst_credit_notes (
    id                  BIGSERIAL       PRIMARY KEY,
    pump_id             BIGINT          NOT NULL,
    client_id           BIGINT          NOT NULL,
    adjustment_id       BIGINT          NOT NULL,
    invoice_id          BIGINT          NOT NULL,
    invoice_line_id     BIGINT          NOT NULL,
    credit_note_number  VARCHAR(16)     NOT NULL,
    financial_year      VARCHAR(7)      NOT NULL,
    sequence_number     INTEGER         NOT NULL,
    issued_on           DATE            NOT NULL,
    reason              VARCHAR(500)    NOT NULL,
    line_type           VARCHAR(10)     NOT NULL,
    description         VARCHAR(300)    NOT NULL,
    hsn_code            VARCHAR(8),
    quantity            NUMERIC(12,3),
    taxable_value       NUMERIC(14,2),
    gst_rate            NUMERIC(5,2),
    cgst                NUMERIC(12,2)   NOT NULL,
    sgst                NUMERIC(12,2)   NOT NULL,
    igst                NUMERIC(12,2)   NOT NULL,
    total               NUMERIC(14,2)   NOT NULL,
    created_by          BIGINT          NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_gcn_pump         FOREIGN KEY (pump_id)         REFERENCES pump_locations (id),
    CONSTRAINT fk_gcn_client       FOREIGN KEY (client_id)       REFERENCES credit_clients (id),
    CONSTRAINT fk_gcn_adjustment   FOREIGN KEY (adjustment_id)   REFERENCES credit_adjustments (id),
    CONSTRAINT fk_gcn_invoice      FOREIGN KEY (invoice_id)      REFERENCES credit_invoices (id),
    CONSTRAINT fk_gcn_invoice_line FOREIGN KEY (invoice_line_id) REFERENCES credit_invoice_lines (id),
    CONSTRAINT fk_gcn_created_by   FOREIGN KEY (created_by)      REFERENCES users (id),
    CONSTRAINT uq_gcn_adjustment   UNIQUE (adjustment_id),
    CONSTRAINT uq_gcn_pump_number  UNIQUE (pump_id, credit_note_number),
    CONSTRAINT uq_gcn_pump_fy_seq  UNIQUE (pump_id, financial_year, sequence_number),
    CONSTRAINT chk_gcn_line_type   CHECK (line_type IN ('FUEL', 'ANCILLARY')),
    CONSTRAINT chk_gcn_total       CHECK (total > 0)
);

CREATE INDEX idx_gcn_invoice ON gst_credit_notes (invoice_id);
CREATE INDEX idx_gcn_client  ON gst_credit_notes (client_id);

-- 5. Group invoices: one invoice for a parent and its sub-accounts, its lines naming the account they were sold to.
ALTER TABLE credit_invoices ADD COLUMN covers_sub_accounts BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE credit_invoice_lines ADD COLUMN account_name VARCHAR(150);
ALTER TABLE pump_locations ADD COLUMN group_invoice_by_default BOOLEAN NOT NULL DEFAULT FALSE;

-- 6. Merging duplicate clients moves the opening balance too, so a client may now hold more than one.
ALTER TABLE credit_opening_balances DROP CONSTRAINT uq_credit_opening_balance_client;
CREATE INDEX idx_credit_opening_balance_client ON credit_opening_balances (client_id);

ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_NOTE_ISSUED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_ADVANCE_REFUNDED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_DEPOSIT_RECEIVED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_DEPOSIT_APPLIED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_DEPOSIT_REFUNDED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_WRITTEN_OFF';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_ACCOUNT_CLOSED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_ACCOUNT_REOPENED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_CLIENTS_MERGED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_DISPUTE_RAISED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_DISPUTE_RESOLVED';
