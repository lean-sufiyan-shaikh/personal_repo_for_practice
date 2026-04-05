-- V3: Add daily-rate payroll support for MANAGER, ADMIN, ACCOUNTANT roles.
--
-- Operators are paid per actual hours worked per shift (HOURLY_SHIFT — added in V2).
-- All other staff roles (MANAGER, ADMIN, ACCOUNTANT) are paid a flat daily rate:
--   days_worked = (period_to - period_from + 1) - leave_days_in_period
--   gross       = days_worked × daily_rate
--
-- payroll_records now carries a salary_type to distinguish the two models.
-- Existing HOURLY_SHIFT records default to 'HOURLY_SHIFT'.

-- ── Users table ───────────────────────────────────────────────────────────────
-- daily_rate is used by MANAGER / ADMIN / ACCOUNTANT only.
-- (Operators use shift1_hourly_rate + standard_hourly_rate added in V2.)
ALTER TABLE users ADD COLUMN daily_rate NUMERIC(10,2);

-- ── Payroll records ───────────────────────────────────────────────────────────
ALTER TABLE payroll_records
    ADD COLUMN salary_type       VARCHAR(20)   NOT NULL DEFAULT 'HOURLY_SHIFT',
    ADD COLUMN total_days        INTEGER,       -- total calendar days in the period (DAILY type)
    ADD COLUMN leave_days        INTEGER,       -- leave days deducted (DAILY type)
    ADD COLUMN days_worked       INTEGER,       -- total_days - leave_days (DAILY type)
    ADD COLUMN daily_rate_snapshot NUMERIC(10,2); -- rate captured at generation time (DAILY type)
