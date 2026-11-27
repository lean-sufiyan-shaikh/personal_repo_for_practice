-- Credit payment corrections: an approved payment can be reversed (a bounced cheque, a payment entered by mistake),
-- cheques carry their bank and date, and credit payments get approval settings of their own instead of the expense
-- threshold. New enum values cannot be used in the transaction that adds them, so no constraint below names them.
ALTER TYPE expense_approval_status ADD VALUE IF NOT EXISTS 'REVERSED';
ALTER TYPE payment_mode ADD VALUE IF NOT EXISTS 'CHEQUE';

-- reference_no holds the cheque number of a cheque payment.
ALTER TABLE credit_payments
    ADD COLUMN cheque_bank VARCHAR(100),
    ADD COLUMN cheque_date DATE;

CREATE INDEX idx_cpay_pump_reference ON credit_payments (pump_id, lower(reference_no), payment_date)
    WHERE reference_no IS NOT NULL;

-- The reversal of one payment, dated the business day it was reversed; the payment keeps its row, marked REVERSED.
-- bounce_charge is a separate debit on the client's account (e.g. the bank's cheque bounce charge).
CREATE TABLE credit_payment_reversals (
    id                    BIGSERIAL       PRIMARY KEY,
    pump_id               BIGINT          NOT NULL,
    client_id             BIGINT          NOT NULL,
    payment_id            BIGINT          NOT NULL,
    amount                NUMERIC(14,2)   NOT NULL,
    bounce_charge         NUMERIC(14,2),
    reversal_date         DATE            NOT NULL,
    reason                VARCHAR(500)    NOT NULL,
    reversed_by_user_id   BIGINT          NOT NULL,
    created_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_cpay_reversal_pump        FOREIGN KEY (pump_id)             REFERENCES pump_locations (id),
    CONSTRAINT fk_cpay_reversal_client      FOREIGN KEY (client_id)           REFERENCES credit_clients (id),
    CONSTRAINT fk_cpay_reversal_payment     FOREIGN KEY (payment_id)          REFERENCES credit_payments (id),
    CONSTRAINT fk_cpay_reversal_reversed_by FOREIGN KEY (reversed_by_user_id) REFERENCES users (id),
    CONSTRAINT uq_cpay_reversal_payment     UNIQUE (payment_id),
    CONSTRAINT chk_cpay_reversal_amount     CHECK (amount > 0),
    CONSTRAINT chk_cpay_reversal_bounce     CHECK (bounce_charge IS NULL OR bounce_charge > 0),
    CONSTRAINT chk_cpay_reversal_reason     CHECK (length(trim(reason)) > 0)
);

CREATE INDEX idx_cpay_reversal_client    ON credit_payment_reversals (client_id);
CREATE INDEX idx_cpay_reversal_pump_date ON credit_payment_reversals (pump_id, reversal_date);

-- Payments above the threshold, or dated more than backdated_payment_approval_days before today, wait for an
-- Owner/Admin when a manager records them. The threshold starts at the expense threshold credit payments used so far.
ALTER TABLE pump_locations
    ADD COLUMN credit_payment_approval_threshold NUMERIC(12,2),
    ADD COLUMN backdated_payment_approval_days   INTEGER NOT NULL DEFAULT 7,
    ADD CONSTRAINT chk_pump_credit_payment_threshold
        CHECK (credit_payment_approval_threshold IS NULL OR credit_payment_approval_threshold > 0),
    ADD CONSTRAINT chk_pump_backdated_payment_days CHECK (backdated_payment_approval_days BETWEEN 0 AND 365);

UPDATE pump_locations
SET credit_payment_approval_threshold = expense_approval_threshold
WHERE expense_approval_threshold > 0;

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'CREDIT_PAYMENT_REVERSED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_PAYMENT_REVERSED';
