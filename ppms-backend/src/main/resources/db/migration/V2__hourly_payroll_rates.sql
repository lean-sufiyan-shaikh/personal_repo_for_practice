-- V2: Replace daily_rate with per-shift hourly rates.
--
-- Business rule (per owner request):
--   SHIFT_1 (00:00–08:00) = night rate, typically higher than day rate.
--   SHIFT_2 (08:00–16:00) and SHIFT_3 (16:00–24:00) share the same standard rate.
--
-- Payroll calculation changes from:
--   days_worked × daily_rate
-- to:
--   actual_shift1_hours × shift1_hourly_rate
--   + actual_standard_hours × standard_hourly_rate
--
-- Payroll record now stores a breakdown (shifts + hours per category)
-- plus a snapshot of the rates used, so historical records are self-contained.

-- ── Users table ───────────────────────────────────────────────────────────────
ALTER TABLE users DROP COLUMN IF EXISTS daily_rate;
ALTER TABLE users ADD COLUMN shift1_hourly_rate   NUMERIC(10,2);   -- SHIFT_1 night rate
ALTER TABLE users ADD COLUMN standard_hourly_rate NUMERIC(10,2);   -- SHIFT_2 + SHIFT_3 day rate

-- ── Payroll records table ─────────────────────────────────────────────────────
ALTER TABLE payroll_records DROP COLUMN IF EXISTS days_worked;

ALTER TABLE payroll_records ADD COLUMN total_shifts          INTEGER      NOT NULL DEFAULT 0;
ALTER TABLE payroll_records ADD COLUMN shift1_shifts         INTEGER      NOT NULL DEFAULT 0;
ALTER TABLE payroll_records ADD COLUMN shift1_hours          NUMERIC(6,2) NOT NULL DEFAULT 0;
ALTER TABLE payroll_records ADD COLUMN shift1_rate_snapshot  NUMERIC(10,2);   -- rate at generation time
ALTER TABLE payroll_records ADD COLUMN standard_shifts       INTEGER      NOT NULL DEFAULT 0;
ALTER TABLE payroll_records ADD COLUMN standard_hours        NUMERIC(6,2) NOT NULL DEFAULT 0;
ALTER TABLE payroll_records ADD COLUMN standard_rate_snapshot NUMERIC(10,2);  -- rate at generation time
