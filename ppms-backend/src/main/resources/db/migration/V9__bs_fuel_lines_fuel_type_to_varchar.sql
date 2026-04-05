-- V9: Convert bs_fuel_lines.fuel_type from a PostgreSQL custom enum to VARCHAR.
--
-- BsFuelLine maps this field as a plain String.
-- Hibernate 6 sends String values as character varying with no implicit cast,
-- which PostgreSQL rejects when the column is a USER-DEFINED enum type.
-- Changing to VARCHAR lets Hibernate insert values without a cast.
-- All existing values (PETROL, DIESEL, CNG) are preserved.

ALTER TABLE bs_fuel_lines
    ALTER COLUMN fuel_type TYPE VARCHAR USING fuel_type::text;
