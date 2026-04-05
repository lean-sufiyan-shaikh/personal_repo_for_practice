-- V5: Enforce phone number uniqueness per pump across all credit clients.
--
-- Rule: a phone number registered with any client (parent or child) in a pump
--       cannot be reused by any other client or sub-account in that same pump.
--
-- Existing duplicates are nullified (keeping the first registered entry per phone)
-- before the constraint is applied so the migration does not fail on live data.

-- ── Step 1: Nullify duplicate phone numbers (keep the lowest id per pump+phone) ──
UPDATE credit_clients
SET phone = NULL
WHERE id NOT IN (
    SELECT MIN(id)
    FROM credit_clients
    WHERE phone IS NOT NULL
    GROUP BY pump_id, phone
);

-- ── Step 2: Drop the old non-unique index (replaced by the unique one below) ────
DROP INDEX IF EXISTS idx_cc_pump_phone;

-- ── Step 3: Add the unique constraint ────────────────────────────────────────────
-- Partial index: NULL phones are ignored, so a client without a phone is always allowed.
CREATE UNIQUE INDEX uq_cc_pump_phone
    ON credit_clients (pump_id, phone)
    WHERE phone IS NOT NULL;
