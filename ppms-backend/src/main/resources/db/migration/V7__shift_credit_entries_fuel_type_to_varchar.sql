-- V7: Convert shift_credit_entries.fuel_type from a PostgreSQL custom enum to VARCHAR.
--
-- The Java entity (ShiftCreditEntry) maps this field as a plain String.
-- Hibernate 6 sends String values as character varying with no implicit cast,
-- which PostgreSQL rejects when the column is a USER-DEFINED enum type.
-- Changing to VARCHAR lets Hibernate insert values without a cast, which is
-- correct since the Java layer already owns all validation of allowed values.

ALTER TABLE shift_credit_entries
    ALTER COLUMN fuel_type TYPE VARCHAR USING fuel_type::text;
