-- V8: Convert balance_sheets.shift_window from a PostgreSQL custom enum to VARCHAR.
--
-- BalanceSheet maps this field as a plain String.
-- Hibernate 6 sends String values as character varying with no implicit cast,
-- which PostgreSQL rejects when the column is a USER-DEFINED enum type.
-- Changing to VARCHAR lets Hibernate query/insert values without a cast.
-- All existing values (SHIFT_1, SHIFT_2, SHIFT_3, NULL) are preserved.

ALTER TABLE balance_sheets
    ALTER COLUMN shift_window TYPE VARCHAR USING shift_window::text;
