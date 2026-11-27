-- Onboarding: a demo pump an owner can explore with sample data, and the balances a credit client already owed when
-- the pump started using PPMS.
ALTER TABLE pump_locations
    ADD COLUMN is_demo BOOLEAN NOT NULL DEFAULT FALSE;

-- At most one demo pump per owner; the index also finds an owner's demo pump.
CREATE UNIQUE INDEX uq_pump_one_demo_per_owner ON pump_locations (owner_id) WHERE is_demo;

-- What a client owed on the day the pump went live, brought in once by the credit client import. It counts as a debit
-- dated as_of_date in the client's balance, ledger, ageing and interest, like a credit sale made that day.
CREATE TABLE credit_opening_balances (
    id                   BIGSERIAL       PRIMARY KEY,
    pump_id              BIGINT          NOT NULL,
    client_id            BIGINT          NOT NULL,
    amount               NUMERIC(14,2)   NOT NULL,
    as_of_date           DATE            NOT NULL,
    created_by_user_id   BIGINT          NOT NULL,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_credit_opening_balance_pump       FOREIGN KEY (pump_id)            REFERENCES pump_locations (id),
    CONSTRAINT fk_credit_opening_balance_client     FOREIGN KEY (client_id)          REFERENCES credit_clients (id),
    CONSTRAINT fk_credit_opening_balance_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id),
    CONSTRAINT uq_credit_opening_balance_client     UNIQUE (client_id),
    CONSTRAINT chk_credit_opening_balance_positive  CHECK (amount > 0)
);

CREATE INDEX idx_credit_opening_balance_pump ON credit_opening_balances (pump_id);

ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'STAFF_IMPORTED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_CLIENTS_IMPORTED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'PRODUCTS_IMPORTED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'TANK_STOCK_IMPORTED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'DEMO_PUMP_CREATED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'DEMO_PUMP_DELETED';
