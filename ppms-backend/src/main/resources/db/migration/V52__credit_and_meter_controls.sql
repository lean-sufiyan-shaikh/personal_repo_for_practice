-- 1. A credit limit of 0 now means no credit at all; "no limit" is its own flag. Clients that had 0 kept
--    unlimited credit until now, so they keep it.
ALTER TABLE credit_clients
    ADD COLUMN unlimited_credit BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE credit_clients SET unlimited_credit = TRUE WHERE credit_limit = 0;

-- 2. Per pump: the limit new clients start with, the highest limit a manager may set, and the share of the
--    limit used at which an account is shown as near its limit.
ALTER TABLE pump_locations
    ADD COLUMN default_credit_limit     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    ADD COLUMN manager_credit_limit_cap NUMERIC(14, 2) NOT NULL DEFAULT 50000,
    ADD COLUMN near_limit_percent       INTEGER        NOT NULL DEFAULT 90,
    ADD CONSTRAINT chk_pump_default_credit_limit CHECK (default_credit_limit >= 0),
    ADD CONSTRAINT chk_pump_manager_credit_limit_cap CHECK (manager_credit_limit_cap >= 0),
    ADD CONSTRAINT chk_pump_near_limit_percent CHECK (near_limit_percent BETWEEN 50 AND 100);

-- 3. Every change of a client's credit limit, with who made it and why.
CREATE TABLE credit_limit_changes (
    id                  BIGSERIAL      PRIMARY KEY,
    pump_id             BIGINT         NOT NULL,
    client_id           BIGINT         NOT NULL,
    old_limit           NUMERIC(14, 2) NOT NULL,
    new_limit           NUMERIC(14, 2) NOT NULL,
    old_unlimited       BOOLEAN        NOT NULL,
    new_unlimited       BOOLEAN        NOT NULL,
    reason              VARCHAR(500)   NOT NULL,
    changed_by_user_id  BIGINT         NOT NULL,
    changed_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_clc_pump       FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_clc_client     FOREIGN KEY (client_id) REFERENCES credit_clients (id) ON DELETE CASCADE,
    CONSTRAINT fk_clc_changed_by FOREIGN KEY (changed_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_clc_limits_not_negative CHECK (old_limit >= 0 AND new_limit >= 0)
);
CREATE INDEX idx_clc_client_changed ON credit_limit_changes (client_id, changed_at DESC);

-- 4. Credit an Owner/Admin let through at shift close for an account over its limit, on hold or overdue.
CREATE TABLE credit_close_exceptions (
    id                  BIGSERIAL      PRIMARY KEY,
    pump_id             BIGINT         NOT NULL,
    shift_id            BIGINT,
    client_id           BIGINT         NOT NULL,
    blocked_reason      VARCHAR(20)    NOT NULL,
    amount              NUMERIC(14, 2) NOT NULL,
    outstanding         NUMERIC(14, 2) NOT NULL,
    effective_limit     NUMERIC(14, 2),
    days_overdue        INTEGER        NOT NULL,
    reason              VARCHAR(500)   NOT NULL,
    allowed_by_user_id  BIGINT         NOT NULL,
    allowed_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_cce_pump       FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_cce_shift      FOREIGN KEY (shift_id) REFERENCES shifts (id),
    CONSTRAINT fk_cce_client     FOREIGN KEY (client_id) REFERENCES credit_clients (id),
    CONSTRAINT fk_cce_allowed_by FOREIGN KEY (allowed_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_cce_blocked_reason CHECK (blocked_reason IN ('OVER_LIMIT', 'ON_HOLD', 'OVERDUE'))
);
CREATE INDEX idx_cce_pump_allowed ON credit_close_exceptions (pump_id, allowed_at);

-- 5. Wrong balance-page PINs per pump and phone (kept as a hash), with lockouts that double each time.
--    PINs set before six digits were required keep working; their strength is learnt at their next use.
CREATE TABLE credit_portal_attempts (
    id               BIGSERIAL   PRIMARY KEY,
    pump_id          BIGINT      NOT NULL,
    phone_hash       VARCHAR(64) NOT NULL,
    client_id        BIGINT,
    failed_attempts  INTEGER     NOT NULL DEFAULT 0,
    lock_count       INTEGER     NOT NULL DEFAULT 0,
    locked_until     TIMESTAMPTZ,
    last_failed_at   TIMESTAMPTZ,
    CONSTRAINT fk_cpa_pump   FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_cpa_client FOREIGN KEY (client_id) REFERENCES credit_clients (id) ON DELETE SET NULL,
    CONSTRAINT uq_cpa_pump_phone UNIQUE (pump_id, phone_hash),
    CONSTRAINT chk_cpa_counts CHECK (failed_attempts >= 0 AND lock_count >= 0)
);

ALTER TABLE credit_clients
    ADD COLUMN portal_pin_weak BOOLEAN;

-- 6. A forward meter adjustment is fuel that left the nozzle without a sale: it is valued at the price of the
--    day, taken out of tank stock as a drain, and needs an Owner/Admin. A manager's request waits for one.
ALTER TABLE nozzle_reading_adjustments
    ADD COLUMN status              VARCHAR(20)    NOT NULL DEFAULT 'APPLIED',
    ADD COLUMN unbilled_litres     NUMERIC(14, 3),
    ADD COLUMN price_per_litre     NUMERIC(10, 2),
    ADD COLUMN unbilled_value      NUMERIC(14, 2),
    ADD COLUMN fuel_dip_entry_id   BIGINT,
    ADD COLUMN reviewed_by_user_id BIGINT,
    ADD COLUMN reviewed_at         TIMESTAMPTZ,
    ADD COLUMN review_reason       VARCHAR(500),
    ADD CONSTRAINT fk_nra_fuel_dip_entry FOREIGN KEY (fuel_dip_entry_id) REFERENCES fuel_dip_entries (id),
    ADD CONSTRAINT fk_nra_reviewed_by FOREIGN KEY (reviewed_by_user_id) REFERENCES users (id),
    ADD CONSTRAINT chk_nra_status CHECK (status IN ('APPLIED', 'PENDING_APPROVAL', 'REJECTED')),
    ADD CONSTRAINT chk_nra_unbilled_litres_positive CHECK (unbilled_litres IS NULL OR unbilled_litres > 0);

CREATE UNIQUE INDEX uq_nra_one_pending_per_nozzle ON nozzle_reading_adjustments (nozzle_id)
    WHERE status = 'PENDING_APPROVAL';

-- 7. One active extension of each type per client. Older duplicates are expired, keeping the latest.
UPDATE credit_extensions ext
SET status = 'EXPIRED'
WHERE ext.status = 'ACTIVE'
  AND EXISTS (SELECT 1
              FROM credit_extensions newer
              WHERE newer.status = 'ACTIVE'
                AND newer.pump_id = ext.pump_id
                AND newer.client_id = ext.client_id
                AND newer.extension_type = ext.extension_type
                AND (newer.created_at > ext.created_at
                     OR (newer.created_at = ext.created_at AND newer.id > ext.id)));

CREATE UNIQUE INDEX uq_cext_one_active_per_type ON credit_extensions (pump_id, client_id, extension_type)
    WHERE status = 'ACTIVE';

-- 8. New audit actions and notifications.
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_CLIENT_UPDATED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_CLIENT_STATUS_CHANGED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_PORTAL_LOCKED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CREDIT_CLOSE_BLOCKED_ALLOWED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'METER_READING_ADJUSTED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'METER_ADJUSTMENT_REQUESTED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'METER_ADJUSTMENT_APPROVED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'METER_ADJUSTMENT_REJECTED';

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'CREDIT_PORTAL_LOCKED';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'CREDIT_CLIENT_PHONE_CHANGED';
