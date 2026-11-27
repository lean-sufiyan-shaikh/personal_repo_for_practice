-- Fleet vehicle control: each credit account can list its approved vehicles, and its vehicle check decides whether a
-- credit entry for a vehicle outside that list (or for a fuel the vehicle may not take, or past its monthly limit) is
-- allowed (OFF), allowed but flagged (WARN), or refused (BLOCK). The check of a group is read from its parent.
ALTER TABLE credit_clients
    ADD COLUMN vehicle_check VARCHAR(5) NOT NULL DEFAULT 'OFF',
    ADD CONSTRAINT chk_credit_client_vehicle_check CHECK (vehicle_check IN ('OFF', 'WARN', 'BLOCK'));

-- registration is stored normalised: upper case letters and digits only. An empty allowed_fuel_types allows every fuel.
-- A vehicle may belong to a sub-account; the parent's account sees and may use every vehicle of the group.
CREATE TABLE client_vehicles (
    id                      BIGSERIAL       PRIMARY KEY,
    pump_id                 BIGINT          NOT NULL,
    client_id               BIGINT          NOT NULL,
    registration            VARCHAR(12)     NOT NULL,
    allowed_fuel_types      VARCHAR(20)[]   NOT NULL DEFAULT '{}',
    monthly_litre_limit     NUMERIC(10,2),
    monthly_amount_limit    NUMERIC(14,2),
    default_driver_name     VARCHAR(100),
    default_driver_phone    VARCHAR(15),
    is_active               BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_client_vehicle_pump   FOREIGN KEY (pump_id)   REFERENCES pump_locations (id),
    CONSTRAINT fk_client_vehicle_client FOREIGN KEY (client_id) REFERENCES credit_clients (id),
    CONSTRAINT chk_client_vehicle_registration CHECK (registration ~ '^[A-Z0-9]{4,12}$'),
    CONSTRAINT chk_client_vehicle_fuel_types CHECK (allowed_fuel_types <@ ARRAY['PETROL', 'SPEED_PETROL', 'DIESEL',
        'SPEED_DIESEL', 'CNG']::VARCHAR(20)[]),
    CONSTRAINT chk_client_vehicle_litre_limit  CHECK (monthly_litre_limit IS NULL OR monthly_litre_limit > 0),
    CONSTRAINT chk_client_vehicle_amount_limit CHECK (monthly_amount_limit IS NULL OR monthly_amount_limit > 0)
);

-- One active vehicle per registration at a pump; a deactivated vehicle frees its registration.
CREATE UNIQUE INDEX uq_client_vehicle_active_registration ON client_vehicles (pump_id, registration) WHERE is_active;
CREATE INDEX idx_client_vehicle_client ON client_vehicles (client_id);

-- vehicle_flag records why a WARN account's entry was let through: UNKNOWN_VEHICLE, FUEL_NOT_ALLOWED or
-- OVER_MONTHLY_LIMIT. litres is the fuel the amount bought at the shift's price, for monthly litre limits and
-- statements. Entries written before this migration keep their free-text registration and no vehicle_id; statements
-- match them to a vehicle whose registration is the same once normalised.
ALTER TABLE shift_credit_entries
    ADD COLUMN vehicle_id     BIGINT,
    ADD COLUMN driver_phone   VARCHAR(15),
    ADD COLUMN litres         NUMERIC(10,2),
    ADD COLUMN vehicle_flag   VARCHAR(20),
    ADD CONSTRAINT fk_credit_entry_vehicle FOREIGN KEY (vehicle_id) REFERENCES client_vehicles (id),
    ADD CONSTRAINT chk_credit_entry_vehicle_flag
        CHECK (vehicle_flag IS NULL OR vehicle_flag IN ('UNKNOWN_VEHICLE', 'FUEL_NOT_ALLOWED', 'OVER_MONTHLY_LIMIT'));

ALTER TABLE ancillary_sales
    ADD COLUMN vehicle_id            BIGINT,
    ADD COLUMN vehicle_registration  VARCHAR(20),
    ADD COLUMN driver_name           VARCHAR(100),
    ADD COLUMN driver_phone          VARCHAR(15),
    ADD COLUMN vehicle_flag          VARCHAR(20),
    ADD CONSTRAINT fk_ancillary_sale_vehicle FOREIGN KEY (vehicle_id) REFERENCES client_vehicles (id),
    ADD CONSTRAINT chk_ancillary_sale_vehicle_flag
        CHECK (vehicle_flag IS NULL OR vehicle_flag IN ('UNKNOWN_VEHICLE', 'OVER_MONTHLY_LIMIT'));

CREATE INDEX idx_credit_entry_vehicle ON shift_credit_entries (vehicle_id) WHERE vehicle_id IS NOT NULL;
CREATE INDEX idx_ancillary_sale_vehicle ON ancillary_sales (vehicle_id) WHERE vehicle_id IS NOT NULL;

-- Litres of the credit entries already on record, at the price the shift sold that fuel at.
UPDATE shift_credit_entries e
SET litres = ROUND(e.amount / price.price_snapshot, 2)
FROM (SELECT shift_id, fuel_type::TEXT AS fuel_type, MAX(price_snapshot) AS price_snapshot
      FROM shift_fuel_readings
      WHERE price_snapshot > 0
      GROUP BY shift_id, fuel_type) price
WHERE price.shift_id = e.shift_id AND price.fuel_type = e.fuel_type;

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'VEHICLE_CREDIT_FLAGGED';

ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CLIENT_VEHICLE_SAVED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'CLIENT_VEHICLE_STATUS_CHANGED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'VEHICLE_CHECK_CHANGED';
