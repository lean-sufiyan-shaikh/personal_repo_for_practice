-- V14: Add enabled flag to pump_locations
-- SuperAdmin can disable a pump; disabled pumps are hidden from the owner's dashboard
-- but remain accessible to assigned staff (ADMIN, MANAGER, OPERATOR) via their direct pump assignment.
-- References: SuperAdmin pump management feature.

ALTER TABLE pump_locations
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
