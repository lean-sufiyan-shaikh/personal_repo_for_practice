-- V6: Relax phone uniqueness to allow sharing within the same parent group.
--
-- New rule: a phone number must be unique across different client groups,
-- but a parent and its sub-accounts may share the same phone number.
-- Group = one root client + all of its direct sub-accounts.
--
-- A simple unique index cannot express this group-aware rule, so we rely on
-- application-level validation (CreditClientController) and drop the strict
-- pump-wide unique index added in V5.

DROP INDEX IF EXISTS uq_cc_pump_phone;
