-- V4: Fix credit client name uniqueness rules.
--
-- Old rule: UNIQUE (pump_id, name) — no two clients at the same pump could share a name,
--           even if one was a sub-account under a completely different parent.
--
-- New rules:
--   • Root/parent clients (parent_client_id IS NULL):
--       Name must be unique within the pump.
--   • Sub-accounts (parent_client_id IS NOT NULL):
--       Name must be unique within the same parent only.
--       The same sub-account name (e.g. "Generator") is allowed under different parents.

ALTER TABLE credit_clients DROP CONSTRAINT uq_cc_pump_name;

-- Root clients: unique name per pump (parent_client_id IS NULL)
CREATE UNIQUE INDEX uq_cc_root_pump_name
    ON credit_clients (pump_id, lower(name))
    WHERE parent_client_id IS NULL;

-- Sub-accounts: unique name per parent (parent_client_id IS NOT NULL)
CREATE UNIQUE INDEX uq_cc_child_parent_name
    ON credit_clients (parent_client_id, lower(name))
    WHERE parent_client_id IS NOT NULL;
