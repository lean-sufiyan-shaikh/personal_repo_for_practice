-- Credit collections: when to remind overdue clients and when to hold their credit, the pump's UPI details for pay
-- links, every reminder given, promises to pay, and the statement each client gets for every month.
ALTER TABLE pump_locations
    ADD COLUMN reminder_stage_days INTEGER[]    NOT NULL DEFAULT '{7,15,30}',
    ADD COLUMN credit_hold_days    INTEGER               DEFAULT 45,
    ADD COLUMN upi_id              VARCHAR(100),
    ADD COLUMN upi_payee_name      VARCHAR(100),
    ADD CONSTRAINT chk_pump_reminder_stage_days CHECK (
        cardinality(reminder_stage_days) <= 10
        AND 1 <= ALL (reminder_stage_days) AND 365 >= ALL (reminder_stage_days)),
    ADD CONSTRAINT chk_pump_credit_hold_days CHECK (credit_hold_days IS NULL OR credit_hold_days BETWEEN 1 AND 365),
    ADD CONSTRAINT chk_pump_upi_details_together CHECK ((upi_id IS NULL) = (upi_payee_name IS NULL));

-- One row per reminder given. overdue_since is the first overdue day of the episode the reminder belongs to, so a
-- payment that ends the episode starts the stages over.
CREATE TABLE credit_reminders (
    id                    BIGSERIAL       PRIMARY KEY,
    pump_id               BIGINT          NOT NULL,
    client_id             BIGINT          NOT NULL,
    overdue_since         DATE            NOT NULL,
    stage_days            INTEGER         NOT NULL,
    days_overdue          INTEGER         NOT NULL,
    outstanding           NUMERIC(14,2)   NOT NULL,
    channel               VARCHAR(20)     NOT NULL,
    method                VARCHAR(20)     NOT NULL,
    note                  VARCHAR(500),
    reminded_by_user_id   BIGINT          NOT NULL,
    reminded_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_credit_reminder_pump        FOREIGN KEY (pump_id)             REFERENCES pump_locations (id),
    CONSTRAINT fk_credit_reminder_client      FOREIGN KEY (client_id)           REFERENCES credit_clients (id),
    CONSTRAINT fk_credit_reminder_reminded_by FOREIGN KEY (reminded_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_credit_reminder_channel    CHECK (channel IN ('MANUAL')),
    CONSTRAINT chk_credit_reminder_method     CHECK (method IN ('CALL', 'IN_PERSON', 'MESSAGE_COPIED')),
    CONSTRAINT chk_credit_reminder_stage      CHECK (stage_days > 0 AND days_overdue >= stage_days)
);

CREATE INDEX idx_credit_reminder_client_episode ON credit_reminders (client_id, overdue_since);

-- A promise pauses reminders until its date. The daily job marks it KEPT once the client has paid the amount since
-- it was recorded, or MISSED the day after its date.
CREATE TABLE credit_payment_promises (
    id                    BIGSERIAL       PRIMARY KEY,
    pump_id               BIGINT          NOT NULL,
    client_id             BIGINT          NOT NULL,
    amount                NUMERIC(14,2)   NOT NULL,
    promised_date         DATE            NOT NULL,
    recorded_on           DATE            NOT NULL,
    note                  VARCHAR(500),
    status                VARCHAR(10)     NOT NULL DEFAULT 'OPEN',
    paid_amount           NUMERIC(14,2),
    recorded_by_user_id   BIGINT          NOT NULL,
    recorded_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    resolved_at           TIMESTAMPTZ,

    CONSTRAINT fk_credit_promise_pump        FOREIGN KEY (pump_id)             REFERENCES pump_locations (id),
    CONSTRAINT fk_credit_promise_client      FOREIGN KEY (client_id)           REFERENCES credit_clients (id),
    CONSTRAINT fk_credit_promise_recorded_by FOREIGN KEY (recorded_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_credit_promise_amount     CHECK (amount > 0),
    CONSTRAINT chk_credit_promise_date       CHECK (promised_date >= recorded_on),
    CONSTRAINT chk_credit_promise_status     CHECK (status IN ('OPEN', 'KEPT', 'MISSED')),
    CONSTRAINT chk_credit_promise_resolved   CHECK ((status = 'OPEN') = (resolved_at IS NULL AND paid_amount IS NULL))
);

CREATE UNIQUE INDEX uq_credit_promise_open_per_client ON credit_payment_promises (client_id) WHERE status = 'OPEN';
CREATE INDEX idx_credit_promise_pump_status ON credit_payment_promises (pump_id, status, promised_date);

-- The statement generated on the 1st for the month before, kept as it was printed so it reads the same every time.
CREATE TABLE credit_monthly_statements (
    id                    BIGSERIAL       PRIMARY KEY,
    pump_id               BIGINT          NOT NULL,
    client_id             BIGINT          NOT NULL,
    period_from           DATE            NOT NULL,
    period_to             DATE            NOT NULL,
    closing_balance       NUMERIC(14,2)   NOT NULL,
    transaction_count     INTEGER         NOT NULL,
    html                  TEXT            NOT NULL,
    generated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_credit_statement_pump   FOREIGN KEY (pump_id)   REFERENCES pump_locations (id),
    CONSTRAINT fk_credit_statement_client FOREIGN KEY (client_id) REFERENCES credit_clients (id),
    CONSTRAINT uq_credit_statement_month  UNIQUE (client_id, period_from),
    CONSTRAINT chk_credit_statement_period CHECK (period_to >= period_from),
    CONSTRAINT chk_credit_statement_count  CHECK (transaction_count >= 0)
);

CREATE INDEX idx_credit_statement_pump_period ON credit_monthly_statements (pump_id, period_from DESC);

-- Lifts the overdue credit hold until its expiry date, granted and revoked like the other extensions.
ALTER TYPE credit_extension_type ADD VALUE IF NOT EXISTS 'CREDIT_HOLD_OVERRIDE';

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'CREDIT_REMINDERS_DUE';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'CREDIT_HOLD_PLACED';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'CREDIT_PROMISE_MISSED';

ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_REMINDER_RECORDED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_PROMISE_RECORDED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'COLLECTION_SETTINGS_UPDATED';
