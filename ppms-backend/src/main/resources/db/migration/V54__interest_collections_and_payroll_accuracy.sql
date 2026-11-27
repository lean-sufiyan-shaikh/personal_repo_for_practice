-- Interest, collections and payroll accuracy. New enum values cannot be used in the transaction that adds them, so no
-- statement below names them.

-- 1. What a waiver means for the waived period: KEEP_PERIOD_CHARGED counts the period as charged, so the next interest
-- run does not charge it again; RECALCULATE leaves it open to be charged again (the only behaviour so far).
ALTER TABLE credit_interest_charges ADD COLUMN waiver_kind VARCHAR(20);

UPDATE credit_interest_charges SET waiver_kind = 'RECALCULATE' WHERE voided_at IS NOT NULL;

ALTER TABLE credit_interest_charges
    ADD CONSTRAINT chk_cic_waiver_kind CHECK (waiver_kind IN ('KEEP_PERIOD_CHARGED', 'RECALCULATE')),
    ADD CONSTRAINT chk_cic_waiver_kind_when_waived CHECK ((voided_at IS NULL) = (waiver_kind IS NULL));

-- 2. Promises can be cancelled, and every edit or cancellation keeps what the promise was before.
ALTER TABLE credit_payment_promises DROP CONSTRAINT chk_credit_promise_status;
ALTER TABLE credit_payment_promises
    ADD CONSTRAINT chk_credit_promise_status CHECK (status IN ('OPEN', 'KEPT', 'MISSED', 'CANCELLED'));

CREATE TABLE credit_payment_promise_changes (
    id                      BIGSERIAL       PRIMARY KEY,
    promise_id              BIGINT          NOT NULL,
    change_type             VARCHAR(10)     NOT NULL,
    previous_amount         NUMERIC(14,2)   NOT NULL,
    previous_promised_date  DATE            NOT NULL,
    new_amount              NUMERIC(14,2),
    new_promised_date       DATE,
    reason                  VARCHAR(500),
    changed_by_user_id      BIGINT          NOT NULL,
    changed_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_credit_promise_change_promise    FOREIGN KEY (promise_id)         REFERENCES credit_payment_promises (id),
    CONSTRAINT fk_credit_promise_change_changed_by FOREIGN KEY (changed_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_credit_promise_change_type      CHECK (change_type IN ('EDITED', 'CANCELLED')),
    CONSTRAINT chk_credit_promise_change_values    CHECK (
        (change_type = 'EDITED') = (new_amount IS NOT NULL AND new_promised_date IS NOT NULL))
);

CREATE INDEX idx_credit_promise_change_promise ON credit_payment_promise_changes (promise_id);

-- 3. Payroll: how and when it was paid, and deductions carried into the next payroll when they exceeded the pay.
ALTER TABLE payroll_records
    ADD COLUMN paid_date              DATE,
    ADD COLUMN payment_mode           VARCHAR(10),
    ADD COLUMN payment_reference      VARCHAR(100),
    ADD COLUMN paid_by                BIGINT,
    ADD COLUMN cash_event_id          BIGINT,
    ADD COLUMN opening_deductions     NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN carried_to_payroll_id  BIGINT;

-- Payroll marked paid before this release is dated by its last update; how it was paid was not recorded.
UPDATE payroll_records
SET paid_date = (updated_at AT TIME ZONE 'Asia/Kolkata')::date
WHERE status = 'PAID';

ALTER TABLE payroll_records
    ADD CONSTRAINT fk_payroll_paid_by       FOREIGN KEY (paid_by)       REFERENCES users (id),
    ADD CONSTRAINT fk_payroll_cash_event    FOREIGN KEY (cash_event_id) REFERENCES cash_events (id),
    ADD CONSTRAINT fk_payroll_carried_to    FOREIGN KEY (carried_to_payroll_id) REFERENCES payroll_records (id)
        ON DELETE SET NULL,
    ADD CONSTRAINT chk_payroll_payment_mode CHECK (payment_mode IN ('CASH', 'BANK', 'UPI')),
    ADD CONSTRAINT chk_payroll_paid_date    CHECK ((status = 'PAID') = (paid_date IS NOT NULL)),
    ADD CONSTRAINT chk_payroll_paid_details CHECK (
        status = 'PAID' OR (payment_mode IS NULL AND payment_reference IS NULL AND paid_by IS NULL AND cash_event_id IS NULL)),
    ADD CONSTRAINT chk_payroll_opening_deductions CHECK (opening_deductions >= 0);

CREATE INDEX idx_payroll_pump_paid_date ON payroll_records (pump_id, paid_date) WHERE paid_date IS NOT NULL;
CREATE INDEX idx_payroll_uncarried_deductions ON payroll_records (pump_id, user_id)
    WHERE uncovered_deductions > 0 AND carried_to_payroll_id IS NULL;

-- A payroll now also takes deductions chosen on the shift itself; such a link has no status to restore.
ALTER TABLE payroll_resolved_shifts ALTER COLUMN previous_status DROP NOT NULL;
CREATE INDEX idx_payroll_resolved_shifts_shift ON payroll_resolved_shifts (shift_id);

-- Salary deductions chosen on the shift before a payroll covering its date was generated were taken by that payroll;
-- link them so they are not taken again. Those chosen after it was generated stay unlinked for the next payroll.
INSERT INTO payroll_resolved_shifts (payroll_id, shift_id, previous_status)
SELECT DISTINCT ON (s.id) p.id, s.id, NULL
FROM shifts s
JOIN payroll_records p
  ON p.pump_id = s.pump_id
 AND p.user_id = s.operator_id
 AND s.shift_date BETWEEN p.period_from AND p.period_to
WHERE s.discrepancy_resolution = 'SALARY_DEDUCTION'
  AND s.discrepancy_type = 'SHORT'
  AND s.discrepancy_amount IS NOT NULL
  AND (s.discrepancy_resolved_at IS NULL OR s.discrepancy_resolved_at <= p.created_at)
  AND NOT EXISTS (SELECT 1 FROM payroll_resolved_shifts prs WHERE prs.shift_id = s.id)
ORDER BY s.id, p.created_at;

ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_PROMISE_EDITED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_PROMISE_CANCELLED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'PAYROLL_PAID';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'PAYROLL_RETURNED_TO_DRAFT';
