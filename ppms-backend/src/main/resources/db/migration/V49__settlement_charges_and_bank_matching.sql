-- 1. A settlement keeps what it cleared from collections (amount_received, the gross), the bank's charges on it
--    (MDR and GST on MDR) and what actually reached the account (net_amount = gross - charges).
--    Earlier settlements had no charges: their net equals their gross.
ALTER TABLE payment_settlements
    ADD COLUMN mdr_amount        NUMERIC(15, 2) NOT NULL DEFAULT 0,
    ADD COLUMN gst_on_mdr_amount NUMERIC(15, 2) NOT NULL DEFAULT 0,
    ADD COLUMN net_amount        NUMERIC(15, 2),
    ADD COLUMN covers_from_date  DATE,
    ADD COLUMN covers_to_date    DATE,
    ADD COLUMN is_partial        BOOLEAN;

UPDATE payment_settlements SET net_amount = amount_received;

-- A zero settlement clears nothing; any recorded earlier is voided so the positive-amount rule can hold.
UPDATE payment_settlements
SET void_status = 'VOIDED',
    void_reason = 'Zero-amount settlement voided when zero amounts stopped being accepted',
    voided_at   = NOW()
WHERE amount_received = 0 AND void_status = 'ACTIVE';

ALTER TABLE payment_settlements
    ALTER COLUMN net_amount SET NOT NULL,
    ADD CONSTRAINT chk_ps_amount_positive CHECK (amount_received > 0 OR void_status = 'VOIDED'),
    ADD CONSTRAINT chk_ps_charges_not_negative CHECK (mdr_amount >= 0 AND gst_on_mdr_amount >= 0),
    ADD CONSTRAINT chk_ps_net_is_gross_less_charges CHECK (net_amount = amount_received - mdr_amount - gst_on_mdr_amount),
    ADD CONSTRAINT chk_ps_net_positive CHECK (net_amount > 0 OR void_status = 'VOIDED'),
    ADD CONSTRAINT chk_ps_covered_dates CHECK (covers_from_date IS NULL OR covers_to_date >= covers_from_date);

-- 2. Default bank charges per pump and payment type: MDR % of the gross and GST % on that MDR.
ALTER TABLE payment_settlement_configs
    ADD COLUMN mdr_percent        NUMERIC(6, 3) NOT NULL DEFAULT 0,
    ADD COLUMN gst_on_mdr_percent NUMERIC(5, 2) NOT NULL DEFAULT 18,
    ADD CONSTRAINT chk_psc_mdr_percent CHECK (mdr_percent >= 0 AND mdr_percent <= 10),
    ADD CONSTRAINT chk_psc_gst_on_mdr_percent CHECK (gst_on_mdr_percent >= 0 AND gst_on_mdr_percent <= 100);

UPDATE payment_settlement_configs SET mdr_percent = 1.8 WHERE payment_type = 'CARD';

-- 3. Bank charges are a cost of the day the settlement reached the bank; stored with DAY sheets like expenses.
ALTER TABLE balance_sheets ADD COLUMN total_bank_charges_amount NUMERIC(14, 2);

-- 4. A statement line can also be matched to a settlement, a cash bank deposit or a supplier payment,
--    or carry an automatic suggestion (SUGGESTED) that a person accepts. A line links to one record at most,
--    and a settlement, deposit or supplier payment is linked from one line at most.
ALTER TYPE bank_line_match_status ADD VALUE IF NOT EXISTS 'SUGGESTED';

ALTER TABLE bank_statement_lines
    ADD COLUMN matched_settlement_id       BIGINT,
    ADD COLUMN matched_deposit_id          BIGINT,
    ADD COLUMN matched_supplier_payment_id BIGINT,
    ADD COLUMN line_hash                   VARCHAR(64),
    ADD CONSTRAINT fk_bsl_settlement       FOREIGN KEY (matched_settlement_id) REFERENCES payment_settlements (id),
    ADD CONSTRAINT fk_bsl_deposit          FOREIGN KEY (matched_deposit_id) REFERENCES bank_deposits (id),
    ADD CONSTRAINT fk_bsl_supplier_payment FOREIGN KEY (matched_supplier_payment_id) REFERENCES fuel_supplier_payments (id);

UPDATE bank_statement_lines
SET matched_shift_id = NULL, matched_ancillary_sale_id = NULL, matched_payment_id = NULL
WHERE match_status <> 'MATCHED';

ALTER TABLE bank_statement_lines
    ADD CONSTRAINT chk_bsl_one_link CHECK (num_nonnulls(matched_shift_id, matched_ancillary_sale_id, matched_payment_id,
        matched_settlement_id, matched_deposit_id, matched_supplier_payment_id) <= 1),
    ADD CONSTRAINT chk_bsl_suggestion_has_link CHECK (match_status::text <> 'SUGGESTED'
        OR num_nonnulls(matched_shift_id, matched_ancillary_sale_id, matched_payment_id,
            matched_settlement_id, matched_deposit_id, matched_supplier_payment_id) = 1),
    ADD CONSTRAINT chk_bsl_unlinked_when_open CHECK (match_status::text NOT IN ('UNMATCHED', 'IGNORED')
        OR num_nonnulls(matched_shift_id, matched_ancillary_sale_id, matched_payment_id,
            matched_settlement_id, matched_deposit_id, matched_supplier_payment_id) = 0);

CREATE UNIQUE INDEX uq_bank_statement_lines_matched_settlement
    ON bank_statement_lines (matched_settlement_id) WHERE matched_settlement_id IS NOT NULL;
CREATE UNIQUE INDEX uq_bank_statement_lines_matched_deposit
    ON bank_statement_lines (matched_deposit_id) WHERE matched_deposit_id IS NOT NULL;
CREATE UNIQUE INDEX uq_bank_statement_lines_matched_supplier_payment
    ON bank_statement_lines (matched_supplier_payment_id) WHERE matched_supplier_payment_id IS NOT NULL;

-- Lines already imported for the pump are recognised by their content hash and skipped on a later import.
CREATE INDEX idx_bank_statement_lines_line_hash ON bank_statement_lines (line_hash) WHERE line_hash IS NOT NULL;
CREATE INDEX idx_bank_statement_lines_utr ON bank_statement_lines (utr_reference) WHERE utr_reference IS NOT NULL;

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'SETTLEMENT_OVERDUE';

ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'BANK_LINE_MATCHED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'BANK_LINE_UNMATCHED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'BANK_LINE_IGNORED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'BANK_LINES_AUTO_MATCHED';
