-- V3: Seed the default Super Admin user on first run.
--
-- This migration runs exactly once (Flyway tracks it in flyway_schema_history).
-- On a fresh database this inserts the super admin account so the system is
-- immediately usable without any manual SQL steps.
--
-- Default credentials:
--   Phone    : 8888888888
--   Password : password
--
-- IMPORTANT: Change the password immediately after first login via the
-- "Change Password" option in the profile menu.
--
-- The hash below is BCrypt(10) of "password".
-- ON CONFLICT DO NOTHING makes this safe to run on a DB that already has the
-- super admin (e.g. if flyway_schema_history was cleared but data was kept).

INSERT INTO users (
    employee_id,
    full_name,
    phone_number,
    password_hash,
    role,
    status
)
VALUES (
    'SA-001',
    'Super Admin',
    '8888888888',
    '$2b$10$645607p6ZsGRIZ7WzkonOeeaDy8ihGfsSPRDGX4NVq4WFbBRCr.ni',
    'SUPER_ADMIN',
    'ACTIVE'
)
ON CONFLICT (phone_number) DO NOTHING;
