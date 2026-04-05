-- =============================================================================
-- V1__complete_schema.sql
-- Petrol Pump Management System (PPMS) — Complete PostgreSQL Schema
-- This migration creates the full schema: enums, tables, indexes, and seed data.
-- =============================================================================

-- =============================================================================
-- EXTENSIONS
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;


-- =============================================================================
-- ENUMS
-- Defined in dependency order — no enum references another.
-- =============================================================================

CREATE TYPE user_role AS ENUM (
    'SUPER_ADMIN',
    'OWNER',
    'ADMIN',
    'MANAGER',
    'OPERATOR',
    'ACCOUNTANT'
);

CREATE TYPE user_status AS ENUM (
    'ACTIVE',
    'INACTIVE'
);

CREATE TYPE fuel_type AS ENUM (
    'PETROL',
    'SPEED_PETROL',
    'DIESEL',
    'SPEED_DIESEL',
    'CNG'
);

CREATE TYPE nozzle_status AS ENUM (
    'ACTIVE',
    'INACTIVE'
);

CREATE TYPE tank_status AS ENUM (
    'ACTIVE',
    'INACTIVE',
    'DECOMMISSIONED'
);

CREATE TYPE lot_status AS ENUM (
    'ACTIVE',
    'EXHAUSTED'
);

CREATE TYPE lot_consumption_source AS ENUM (
    'SHIFT_CLOSE',
    'DIP_CORRECTION'
);

CREATE TYPE shift_window AS ENUM (
    'SHIFT_1',
    'SHIFT_2',
    'SHIFT_3'
);

CREATE TYPE shift_status AS ENUM (
    'OPEN',
    'OPEN_OVERDUE',
    'AUTO_CLOSED_OVERDUE',
    'CLOSED_BALANCED',
    'CLOSED_DISCREPANCY_PENDING',
    'CLOSED_DISCREPANCY_RESOLVED'
);

CREATE TYPE discrepancy_type AS ENUM (
    'SHORT',
    'OVER'
);

CREATE TYPE discrepancy_resolution AS ENUM (
    'SALARY_DEDUCTION',
    'CASH_RECOVERY',
    'WAIVED',
    'PENDING_INVESTIGATION'
);

CREATE TYPE payment_mode AS ENUM (
    'CASH',
    'UPI',
    'BANK_TRANSFER',
    'OTHER'
);

CREATE TYPE transaction_payment_mode AS ENUM (
    'CASH',
    'UPI',
    'CARD',
    'FLEET_CARD',
    'CREDIT'
);

CREATE TYPE cash_event_type AS ENUM (
    'OPENING_BALANCE',
    'CASH_IN',
    'CASH_OUT',
    'CLOSING_BALANCE'
);

CREATE TYPE expense_category AS ENUM (
    'FUEL',
    'MAINTENANCE',
    'SALARY',
    'UTILITIES',
    'EQUIPMENT',
    'OTHER'
);

CREATE TYPE expense_approval_status AS ENUM (
    'PENDING_APPROVAL',
    'APPROVED',
    'REJECTED'
);

CREATE TYPE notification_type AS ENUM (
    'LOW_STOCK',
    'PRICE_STALE',
    'DOCUMENT_EXPIRING',
    'CALIBRATION_DUE',
    'SHIFT_OVERDUE',
    'ZERO_SALE_SHIFT',
    'PRICE_CHANGE_OPEN_SHIFT',
    'ANCILLARY_LOW_STOCK',
    'AUTO_CLOSED_SHIFT'
);

CREATE TYPE audit_action AS ENUM (
    'USER_CREATED',
    'USER_DEACTIVATED',
    'USER_STATUS_CHANGED',
    'FUEL_PRICE_UPDATED',
    'SHIFT_OPENED',
    'SHIFT_CLOSED',
    'DISCREPANCY_RESOLVED',
    'CREDIT_ENTRY_VOIDED',
    'CREDIT_LIMIT_CHANGED',
    'CREDIT_PAYMENT_RECEIVED',
    'INTEREST_APPLIED',
    'CREDIT_CLIENT_CREATED',
    'CREDIT_CLIENT_DELETED',
    'CREDIT_ENTRY_REASSIGNED',
    'DELIVERY_RECORDED',
    'DIP_CHECK_RECORDED',
    'DOCUMENT_ADDED',
    'EXPENSE_RECORDED',
    'LOGIN',
    'LOGIN_FAILED',
    'TOKEN_REVOKED',
    'PAYROLL_APPROVED',
    'PUMP_CREATED',
    'PUMP_DELETED',
    'NOZZLE_ADDED',
    'TANK_ADDED',
    'ANCILLARY_SALE_RECORDED',
    'ANCILLARY_DELIVERY_RECORDED',
    'HANDOVER_COMPLETED',
    'PUMP_CLOSURE_ADDED',
    'BANK_STATEMENT_IMPORTED',
    'SUPPLIER_PAYMENT_RECORDED',
    'PUMP_CONFIG_UPDATED'
);

CREATE TYPE payroll_status AS ENUM (
    'DRAFT',
    'APPROVED',
    'PAID'
);

CREATE TYPE document_status AS ENUM (
    'VALID',
    'EXPIRING_SOON',
    'EXPIRED'
);

CREATE TYPE balance_sheet_report_type AS ENUM (
    'SHIFT',
    'DAY'
);

CREATE TYPE billing_cycle AS ENUM (
    'WEEKLY',
    'MONTHLY'
);

CREATE TYPE interest_period AS ENUM (
    'WEEKLY',
    'MONTHLY'
);

CREATE TYPE credit_extension_status AS ENUM (
    'ACTIVE',
    'EXPIRED'
);

CREATE TYPE credit_extension_type AS ENUM (
    'AMOUNT_EXTENSION',
    'BILLING_CYCLE_EXTENSION',
    'OVERDUE_BLOCK_WAIVER'
);

CREATE TYPE ancillary_product_status AS ENUM (
    'ACTIVE',
    'INACTIVE'
);

CREATE TYPE ancillary_lot_status AS ENUM (
    'ACTIVE',
    'EXHAUSTED'
);

CREATE TYPE unit_of_measure AS ENUM (
    'LITRE',
    'KG',
    'PIECE'
);

CREATE TYPE async_job_status AS ENUM (
    'QUEUED',
    'PROCESSING',
    'DONE',
    'FAILED'
);

CREATE TYPE bank_line_match_status AS ENUM (
    'UNMATCHED',
    'MATCHED',
    'IGNORED'
);


-- =============================================================================
-- SECTION 1: CORE USERS & PUMPS
-- users and pump_locations reference each other (users.assigned_pump_id -> pump_locations).
-- Resolved with a DEFERRABLE FK on users.assigned_pump_id so both can be created
-- in the same transaction without ordering issues during onboarding.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- users
-- Central identity table for all staff. Roles control access across the app.
-- assigned_pump_id is nullable — SUPER_ADMIN and OWNER may not be assigned to
-- a single pump. The deferrable FK allows creating a pump and its first user
-- in the same transaction without ordering constraints.
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id                  BIGSERIAL       PRIMARY KEY,
    employee_id         VARCHAR(50)     NOT NULL,
    full_name           VARCHAR(150)    NOT NULL,
    phone_number        VARCHAR(15)     NOT NULL,
    password_hash       VARCHAR(255)    NOT NULL,
    email               VARCHAR(255),
    address             TEXT,
    role                user_role       NOT NULL,
    status              user_status     NOT NULL DEFAULT 'ACTIVE',
    date_of_joining     DATE,
    assigned_pump_id    BIGINT,         -- Deferrable FK to pump_locations (defined below)
    daily_rate          NUMERIC(10,2),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_users_employee_id    UNIQUE (employee_id),
    CONSTRAINT uq_users_phone_number   UNIQUE (phone_number),
    CONSTRAINT uq_users_email          UNIQUE (email)
);

-- -----------------------------------------------------------------------------
-- pump_locations
-- A pump location is a physical petrol station. One owner can manage multiple
-- pumps. Discrepancy and expense thresholds are per-pump configurable.
-- -----------------------------------------------------------------------------
CREATE TABLE pump_locations (
    id                                  BIGSERIAL       PRIMARY KEY,
    owner_id                            BIGINT          NOT NULL,
    name                                VARCHAR(150)    NOT NULL,
    address                             TEXT,
    max_nozzle_count                    INTEGER         NOT NULL DEFAULT 4,
    manager_id                          BIGINT,
    admin_id                            BIGINT,
    discrepancy_escalation_threshold    NUMERIC(12,2),  -- NULL = no escalation rule
    expense_approval_threshold          NUMERIC(12,2),  -- NULL = all expenses auto-approved
    created_at                          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_pump_owner       FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT uq_pump_owner_name  UNIQUE (owner_id, name)
);

-- Now that pump_locations exists, add the deferrable FK on users.assigned_pump_id.
-- DEFERRABLE INITIALLY DEFERRED means the FK is only checked at transaction commit,
-- not at row-insertion time — critical for onboarding flows that create pump and user together.
ALTER TABLE users
    ADD CONSTRAINT fk_users_assigned_pump
    FOREIGN KEY (assigned_pump_id)
    REFERENCES pump_locations (id)
    DEFERRABLE INITIALLY DEFERRED;

-- -----------------------------------------------------------------------------
-- underground_tanks
-- Physical fuel storage tanks at a pump location.
-- dip_tolerance is the acceptable variance (in litres) between a manual dip
-- reading and the system's calculated stock before an alert is raised.
-- -----------------------------------------------------------------------------
CREATE TABLE underground_tanks (
    id                  BIGSERIAL       PRIMARY KEY,
    pump_id             BIGINT          NOT NULL,
    tank_identifier     VARCHAR(50)     NOT NULL,
    fuel_type           fuel_type       NOT NULL,
    capacity            NUMERIC(12,3)   NOT NULL,
    current_stock       NUMERIC(12,3)   NOT NULL DEFAULT 0,
    dip_tolerance       NUMERIC(8,3)    NOT NULL DEFAULT 20.000,
    status              tank_status     NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_tank_pump              FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT uq_tank_pump_identifier   UNIQUE (pump_id, tank_identifier),
    CONSTRAINT chk_tank_capacity_positive    CHECK (capacity > 0),
    CONSTRAINT chk_tank_stock_non_negative   CHECK (current_stock >= 0)
);

-- -----------------------------------------------------------------------------
-- nozzles
-- A nozzle is a physical dispensing gun at a pump. One nozzle can dispense
-- multiple fuel types via separate outlets (see nozzle_outlets).
-- max_meter_value is the rollover point of the nozzle's mechanical counter.
-- -----------------------------------------------------------------------------
CREATE TABLE nozzles (
    id                  BIGSERIAL       PRIMARY KEY,
    pump_id             BIGINT          NOT NULL,
    nozzle_number       INTEGER         NOT NULL,
    status              nozzle_status   NOT NULL DEFAULT 'ACTIVE',
    max_meter_value     NUMERIC(12,3)   NOT NULL DEFAULT 999999.999,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_nozzle_pump      FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT uq_nozzle_pump_num  UNIQUE (pump_id, nozzle_number)
);

-- -----------------------------------------------------------------------------
-- nozzle_outlets
-- Each nozzle can have one or more outlets, one per fuel type.
-- last_reading tracks the cumulative meter reading for this outlet.
-- tank_id links to the underground tank this outlet draws from.
-- -----------------------------------------------------------------------------
CREATE TABLE nozzle_outlets (
    id              BIGSERIAL       PRIMARY KEY,
    nozzle_id       BIGINT          NOT NULL,
    fuel_type       fuel_type       NOT NULL,
    last_reading    NUMERIC(12,3)   NOT NULL DEFAULT 0,
    tank_id         BIGINT,         -- nullable: linked at setup, not required immediately
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_outlet_nozzle    FOREIGN KEY (nozzle_id) REFERENCES nozzles (id) ON DELETE CASCADE,
    CONSTRAINT fk_outlet_tank      FOREIGN KEY (tank_id) REFERENCES underground_tanks (id),
    CONSTRAINT uq_outlet_nozzle_fuel  UNIQUE (nozzle_id, fuel_type)
);


-- =============================================================================
-- SECTION 2: FUEL PRICING & SUPPLIERS
-- =============================================================================

-- -----------------------------------------------------------------------------
-- global_fuel_prices
-- Stores the price history per fuel type per pump. The active price is the one
-- with the latest effective_from timestamp for a given (pump_id, fuel_type).
-- -----------------------------------------------------------------------------
CREATE TABLE global_fuel_prices (
    id                  BIGSERIAL       PRIMARY KEY,
    pump_id             BIGINT          NOT NULL,
    fuel_type           fuel_type       NOT NULL,
    price_per_litre     NUMERIC(10,3)   NOT NULL,
    effective_from      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    set_by_user_id      BIGINT          NOT NULL,

    CONSTRAINT fk_gfp_pump      FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_gfp_set_by    FOREIGN KEY (set_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_gfp_price_positive   CHECK (price_per_litre > 0)
);

-- -----------------------------------------------------------------------------
-- fuel_suppliers
-- Vendors who deliver fuel to pump locations.
-- -----------------------------------------------------------------------------
CREATE TABLE fuel_suppliers (
    id              BIGSERIAL       PRIMARY KEY,
    pump_id         BIGINT          NOT NULL,
    name            VARCHAR(150)    NOT NULL,
    contact_name    VARCHAR(150),
    phone           VARCHAR(15),
    email           VARCHAR(255),
    notes           TEXT,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_supplier_pump    FOREIGN KEY (pump_id) REFERENCES pump_locations (id)
);


-- =============================================================================
-- SECTION 3: INVENTORY
-- =============================================================================

-- -----------------------------------------------------------------------------
-- tanker_deliveries
-- Records each physical fuel delivery to a tank. Linked to a supplier and the
-- specific underground tank that was filled. Cost price per unit enables FIFO
-- cost-of-goods calculations in reports.
-- -----------------------------------------------------------------------------
CREATE TABLE tanker_deliveries (
    id                      BIGSERIAL       PRIMARY KEY,
    pump_id                 BIGINT          NOT NULL,
    tank_id                 BIGINT          NOT NULL,
    supplier_id             BIGINT,         -- nullable: delivery may be from an ad-hoc source
    fuel_type               fuel_type       NOT NULL,
    quantity_delivered      NUMERIC(12,3)   NOT NULL,
    cost_price_per_unit     NUMERIC(10,3)   NOT NULL,
    delivery_date           TIMESTAMPTZ     NOT NULL,
    invoice_reference       VARCHAR(100),
    logged_by_user_id       BIGINT          NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_delivery_pump         FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_delivery_tank         FOREIGN KEY (tank_id) REFERENCES underground_tanks (id),
    CONSTRAINT fk_delivery_supplier     FOREIGN KEY (supplier_id) REFERENCES fuel_suppliers (id),
    CONSTRAINT fk_delivery_logged_by    FOREIGN KEY (logged_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_delivery_qty_positive        CHECK (quantity_delivered > 0),
    CONSTRAINT chk_delivery_cost_positive       CHECK (cost_price_per_unit > 0)
);

-- -----------------------------------------------------------------------------
-- inventory_lots
-- Each delivery creates one or more inventory lots for FIFO cost tracking.
-- DIP adjustments also create lots (is_dip_adjustment = TRUE, tanker_delivery_id = NULL).
-- remaining_quantity decreases as fuel is sold; status moves to EXHAUSTED at 0.
-- -----------------------------------------------------------------------------
CREATE TABLE inventory_lots (
    id                      BIGSERIAL       PRIMARY KEY,
    tanker_delivery_id      BIGINT,         -- nullable for DIP adjustment lots
    tank_id                 BIGINT          NOT NULL,
    pump_id                 BIGINT          NOT NULL,
    fuel_type               fuel_type       NOT NULL,
    original_quantity       NUMERIC(12,3)   NOT NULL,
    remaining_quantity      NUMERIC(12,3)   NOT NULL,
    cost_price_per_unit     NUMERIC(10,3)   NOT NULL,
    delivery_date           TIMESTAMPTZ     NOT NULL,
    is_dip_adjustment       BOOLEAN         NOT NULL DEFAULT FALSE,
    status                  lot_status      NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_lot_delivery  FOREIGN KEY (tanker_delivery_id) REFERENCES tanker_deliveries (id),
    CONSTRAINT fk_lot_tank      FOREIGN KEY (tank_id) REFERENCES underground_tanks (id),
    CONSTRAINT fk_lot_pump      FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT chk_lot_orig_qty_positive        CHECK (original_quantity > 0),
    CONSTRAINT chk_lot_remaining_non_negative   CHECK (remaining_quantity >= 0)
);

-- -----------------------------------------------------------------------------
-- lot_consumptions
-- Tracks how much was consumed from each lot per shift close or DIP correction.
-- This is the FIFO ledger — every litre sold is traceable to a specific lot.
-- shift_id is nullable because DIP corrections are not tied to a shift.
-- -----------------------------------------------------------------------------
CREATE TABLE lot_consumptions (
    id                      BIGSERIAL               PRIMARY KEY,
    lot_id                  BIGINT                  NOT NULL,
    shift_id                BIGINT,                 -- FK added after shifts table is created
    dip_correction_id       BIGINT,                 -- populated when source_type = DIP_CORRECTION
    quantity_consumed       NUMERIC(12,3)           NOT NULL,
    cost_price_per_unit     NUMERIC(10,3)           NOT NULL,
    source_type             lot_consumption_source  NOT NULL DEFAULT 'SHIFT_CLOSE',
    consumed_at             TIMESTAMPTZ             NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_consumption_lot   FOREIGN KEY (lot_id) REFERENCES inventory_lots (id),
    CONSTRAINT chk_consumption_qty_positive CHECK (quantity_consumed > 0)
);

-- -----------------------------------------------------------------------------
-- dip_checks
-- Manual dip measurements of tank levels, compared against system stock.
-- Variance = dip_reading - system_stock (negative = loss, positive = gain).
-- is_within_tolerance is pre-calculated relative to underground_tanks.dip_tolerance.
-- -----------------------------------------------------------------------------
CREATE TABLE dip_checks (
    id                      BIGSERIAL       PRIMARY KEY,
    pump_id                 BIGINT          NOT NULL,
    tank_id                 BIGINT          NOT NULL,
    measured_quantity       NUMERIC(12,3)   NOT NULL,
    system_stock            NUMERIC(12,3)   NOT NULL,
    variance                NUMERIC(12,3)   NOT NULL GENERATED ALWAYS AS (measured_quantity - system_stock) STORED,
    notes                   TEXT,
    checked_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    logged_by_user_id       BIGINT          NOT NULL,
    checked_by_user_id      BIGINT,
    status                  VARCHAR(30)     NOT NULL DEFAULT 'WITHIN_TOLERANCE',
    reviewed_by_user_id     BIGINT,
    reviewed_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_dip_pump          FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_dip_tank          FOREIGN KEY (tank_id) REFERENCES underground_tanks (id),
    CONSTRAINT fk_dip_logged_by     FOREIGN KEY (logged_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_dip_checked_by    FOREIGN KEY (checked_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_dip_reviewed_by   FOREIGN KEY (reviewed_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_dip_reading_non_negative  CHECK (measured_quantity >= 0)
);


-- =============================================================================
-- SECTION 4: SHIFTS & OPERATIONS
-- =============================================================================

-- -----------------------------------------------------------------------------
-- shifts
-- The core operational unit. A shift represents one operator's work period on
-- one nozzle. Cash/UPI/card/fleet/credit totals are filled at shift close.
-- Discrepancy fields are populated when actual collections don't match expected.
-- is_overdue_flag is set by the scheduler if a shift is not closed on time.
-- -----------------------------------------------------------------------------
CREATE TABLE shifts (
    id                              BIGSERIAL                   PRIMARY KEY,
    pump_id                         BIGINT                      NOT NULL,
    nozzle_id                       BIGINT                      NOT NULL,
    operator_id                     BIGINT                      NOT NULL,
    opened_by_user_id               BIGINT                      NOT NULL,
    closed_by_user_id               BIGINT,
    shift_window                    shift_window                NOT NULL,
    shift_date                      DATE                        NOT NULL,
    actual_start_time               TIMESTAMPTZ                 NOT NULL,
    actual_end_time                 TIMESTAMPTZ,
    cash_collected                  NUMERIC(14,2),
    upi_collected                   NUMERIC(14,2),
    card_collected                  NUMERIC(14,2),
    fleet_card_collected            NUMERIC(14,2),
    credit_total                    NUMERIC(14,2),
    total_amount_due                NUMERIC(14,2),
    discrepancy_amount              NUMERIC(14,2),
    discrepancy_type                discrepancy_type,
    discrepancy_reason              TEXT,
    discrepancy_resolution          discrepancy_resolution,
    discrepancy_resolution_note     TEXT,
    discrepancy_resolved_by_id      BIGINT,
    discrepancy_resolved_at         TIMESTAMPTZ,
    status                          shift_status                NOT NULL DEFAULT 'OPEN',
    is_overdue_flag                 BOOLEAN                     NOT NULL DEFAULT FALSE,
    created_at                      TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_shift_pump                FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_shift_nozzle              FOREIGN KEY (nozzle_id) REFERENCES nozzles (id),
    CONSTRAINT fk_shift_operator            FOREIGN KEY (operator_id) REFERENCES users (id),
    CONSTRAINT fk_shift_opened_by           FOREIGN KEY (opened_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_shift_closed_by           FOREIGN KEY (closed_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_shift_resolved_by         FOREIGN KEY (discrepancy_resolved_by_id) REFERENCES users (id)
);

-- Now that shifts exists, add the FK from lot_consumptions.shift_id
ALTER TABLE lot_consumptions
    ADD CONSTRAINT fk_consumption_shift
    FOREIGN KEY (shift_id) REFERENCES shifts (id);

-- -----------------------------------------------------------------------------
-- shift_fuel_readings
-- Opening and closing meter readings for each outlet in a shift.
-- price_snapshot is the fuel price frozen at shift open — insulates shift
-- revenue from any mid-shift price changes.
-- tank_id is frozen at shift open time for accurate FIFO lot attribution.
-- -----------------------------------------------------------------------------
CREATE TABLE shift_fuel_readings (
    id              BIGSERIAL       PRIMARY KEY,
    shift_id        BIGINT          NOT NULL,
    outlet_id       BIGINT          NOT NULL,
    fuel_type       fuel_type       NOT NULL,
    tank_id         BIGINT,         -- nullable: frozen at shift open; used for lot attribution
    start_reading   NUMERIC(12,3)   NOT NULL,
    end_reading     NUMERIC(12,3),
    units_sold      NUMERIC(12,3),
    price_snapshot  NUMERIC(10,3)   NOT NULL,

    CONSTRAINT fk_sfr_shift     FOREIGN KEY (shift_id) REFERENCES shifts (id) ON DELETE CASCADE,
    CONSTRAINT fk_sfr_outlet    FOREIGN KEY (outlet_id) REFERENCES nozzle_outlets (id),
    CONSTRAINT fk_sfr_tank      FOREIGN KEY (tank_id) REFERENCES underground_tanks (id),
    CONSTRAINT uq_sfr_shift_outlet  UNIQUE (shift_id, outlet_id)
);

-- -----------------------------------------------------------------------------
-- fuel_transactions
-- Optional per-transaction logging during a shift (not all pumps use this).
-- vehicle_registration and utr_reference support UPI reconciliation.
-- -----------------------------------------------------------------------------
CREATE TABLE fuel_transactions (
    id                      BIGSERIAL                   PRIMARY KEY,
    shift_id                BIGINT                      NOT NULL,
    pump_id                 BIGINT                      NOT NULL,
    nozzle_outlet_id        BIGINT                      NOT NULL,
    fuel_type               fuel_type                   NOT NULL,
    quantity_litres         NUMERIC(10,3)               NOT NULL,
    price_per_unit          NUMERIC(10,4)               NOT NULL,
    total_amount            NUMERIC(12,2)               NOT NULL,
    payment_mode            transaction_payment_mode    NOT NULL DEFAULT 'CASH',
    vehicle_registration    VARCHAR(20),
    upi_reference           VARCHAR(50),
    notes                   TEXT,
    recorded_by_user_id     BIGINT                      NOT NULL,
    recorded_at             TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_ftxn_shift        FOREIGN KEY (shift_id) REFERENCES shifts (id),
    CONSTRAINT fk_ftxn_pump         FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_ftxn_outlet       FOREIGN KEY (nozzle_outlet_id) REFERENCES nozzle_outlets (id),
    CONSTRAINT fk_ftxn_recorded_by  FOREIGN KEY (recorded_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_ftxn_litres_positive  CHECK (quantity_litres > 0),
    CONSTRAINT chk_ftxn_amount_positive  CHECK (total_amount > 0)
);


-- =============================================================================
-- SECTION 5: CREDIT SYSTEM
-- =============================================================================

-- -----------------------------------------------------------------------------
-- credit_clients
-- Businesses or fleet operators who purchase fuel on credit.
-- parent_client_id enables sub-account hierarchy (e.g. HQ and branch accounts).
-- Interest settings are per-client and only applied when is_interest_enabled = TRUE.
-- -----------------------------------------------------------------------------
CREATE TABLE credit_clients (
    id                      BIGSERIAL       PRIMARY KEY,
    pump_id                 BIGINT          NOT NULL,
    name                    VARCHAR(150)    NOT NULL,
    phone                   VARCHAR(15),
    notes                   TEXT,
    credit_limit            NUMERIC(14,2)   NOT NULL DEFAULT 0,
    billing_cycle           billing_cycle   NOT NULL DEFAULT 'MONTHLY',
    monthly_interest_rate   NUMERIC(5,2)    NOT NULL DEFAULT 0,
    interest_period         interest_period NOT NULL DEFAULT 'MONTHLY',
    interest_grace_days     INTEGER         NOT NULL DEFAULT 1,
    parent_client_id        BIGINT,         -- nullable: self-referencing for sub-accounts
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_cc_pump           FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_cc_parent         FOREIGN KEY (parent_client_id) REFERENCES credit_clients (id),
    CONSTRAINT uq_cc_pump_name      UNIQUE (pump_id, name)
);

-- -----------------------------------------------------------------------------
-- shift_credit_entries
-- Credit sales recorded during a shift. Linked to a credit client when known;
-- client_name is always stored as a snapshot to handle client renames.
-- void_status tracks whether this entry was later voided (e.g. data entry error).
-- -----------------------------------------------------------------------------
CREATE TABLE shift_credit_entries (
    id                      BIGSERIAL       PRIMARY KEY,
    shift_id                BIGINT          NOT NULL,
    client_id               BIGINT,         -- nullable: may be unknown/unregistered client
    client_name             VARCHAR(150)    NOT NULL,
    bill_no                 VARCHAR(50),
    amount                  NUMERIC(14,2)   NOT NULL,
    fuel_type               fuel_type       NOT NULL,
    description             TEXT,
    vehicle_registration    VARCHAR(20),
    driver_name             VARCHAR(100),
    void_status             VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    void_reason             TEXT,
    voided_by_user_id       BIGINT,
    voided_at               TIMESTAMPTZ,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_sce_shift         FOREIGN KEY (shift_id) REFERENCES shifts (id),
    CONSTRAINT fk_sce_client        FOREIGN KEY (client_id) REFERENCES credit_clients (id),
    CONSTRAINT fk_sce_voided_by     FOREIGN KEY (voided_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_sce_amount_positive  CHECK (amount > 0)
);

-- -----------------------------------------------------------------------------
-- credit_payments
-- Cash/UPI/transfer payments received from credit clients to settle their dues.
-- payment_approval_status allows high-value payments to require manager approval.
-- -----------------------------------------------------------------------------
CREATE TABLE credit_payments (
    id                          BIGSERIAL               PRIMARY KEY,
    pump_id                     BIGINT                  NOT NULL,
    client_id                   BIGINT                  NOT NULL,
    amount                      NUMERIC(14,2)           NOT NULL,
    payment_date                DATE                    NOT NULL,
    payment_mode                payment_mode            NOT NULL DEFAULT 'CASH',
    reference_no                VARCHAR(100),
    notes                       TEXT,
    received_by_user_id         BIGINT                  NOT NULL,
    payment_approval_status     expense_approval_status NOT NULL DEFAULT 'APPROVED',
    approved_by_user_id         BIGINT,
    approved_at                 TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ             NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_cpay_pump         FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_cpay_client       FOREIGN KEY (client_id) REFERENCES credit_clients (id),
    CONSTRAINT fk_cpay_received_by  FOREIGN KEY (received_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_cpay_approved_by  FOREIGN KEY (approved_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_cpay_amount_positive  CHECK (amount > 0)
);

-- -----------------------------------------------------------------------------
-- credit_interest_charges
-- System-generated interest charges applied to outstanding credit balances.
-- Stored separately from payments so they appear as line items on statements.
-- -----------------------------------------------------------------------------
CREATE TABLE credit_interest_charges (
    id                      BIGSERIAL       PRIMARY KEY,
    pump_id                 BIGINT          NOT NULL,
    client_id               BIGINT          NOT NULL,
    amount                  NUMERIC(14,2)   NOT NULL,
    outstanding_balance     NUMERIC(14,2)   NOT NULL,
    rate_applied            NUMERIC(5,2)    NOT NULL,
    days_applied            INTEGER         NOT NULL,
    period_from             DATE            NOT NULL,
    period_to               DATE            NOT NULL,
    source                  VARCHAR(100)    NOT NULL,
    applied_by_user_id      BIGINT,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_cic_pump              FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_cic_client            FOREIGN KEY (client_id) REFERENCES credit_clients (id),
    CONSTRAINT fk_cic_applied_by        FOREIGN KEY (applied_by_user_id) REFERENCES users (id)
);

-- -----------------------------------------------------------------------------
-- credit_extensions
-- Temporary increases to a client's credit limit, billing cycle grace periods,
-- or waivers on overdue blocking. Status moves to EXPIRED after valid_until.
-- -----------------------------------------------------------------------------
CREATE TABLE credit_extensions (
    id                  BIGSERIAL               PRIMARY KEY,
    pump_id             BIGINT                  NOT NULL,
    client_id           BIGINT                  NOT NULL,
    extension_type      credit_extension_type   NOT NULL,
    extension_amount    NUMERIC(14,2),          -- nullable: only for AMOUNT_EXTENSION type
    expiry_date         DATE                    NOT NULL,
    status              credit_extension_status NOT NULL DEFAULT 'ACTIVE',
    reason              TEXT                    NOT NULL,
    granted_by_user_id  BIGINT                  NOT NULL,
    created_at          TIMESTAMPTZ             NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ             NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_cext_pump         FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_cext_client       FOREIGN KEY (client_id) REFERENCES credit_clients (id),
    CONSTRAINT fk_cext_granted_by   FOREIGN KEY (granted_by_user_id) REFERENCES users (id)
);

-- -----------------------------------------------------------------------------
-- credit_entry_reassignments
-- Audit trail for moving a credit entry from one client to another.
-- The shift_credit_entries record itself is updated; this table preserves history.
-- -----------------------------------------------------------------------------
CREATE TABLE credit_entry_reassignments (
    id                      BIGSERIAL       PRIMARY KEY,
    credit_entry_id         BIGINT          NOT NULL,
    from_client_id          BIGINT          NOT NULL,
    to_client_id            BIGINT          NOT NULL,
    reassigned_by_user_id   BIGINT          NOT NULL,
    reason                  TEXT            NOT NULL,
    reassigned_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_cer_entry         FOREIGN KEY (credit_entry_id) REFERENCES shift_credit_entries (id),
    CONSTRAINT fk_cer_from_client   FOREIGN KEY (from_client_id) REFERENCES credit_clients (id),
    CONSTRAINT fk_cer_to_client     FOREIGN KEY (to_client_id) REFERENCES credit_clients (id),
    CONSTRAINT fk_cer_reassigned_by FOREIGN KEY (reassigned_by_user_id) REFERENCES users (id)
);


-- =============================================================================
-- SECTION 6: REPORTS
-- =============================================================================

-- -----------------------------------------------------------------------------
-- balance_sheets
-- Aggregated financial summaries per shift or per day. These are computed/cached
-- snapshots — they are not derived on-the-fly. report_type = DAY has shift_window = NULL.
-- -----------------------------------------------------------------------------
CREATE TABLE balance_sheets (
    id                          BIGSERIAL                   PRIMARY KEY,
    pump_id                     BIGINT                      NOT NULL,
    report_type                 balance_sheet_report_type   NOT NULL,
    report_date                 DATE                        NOT NULL,
    shift_window                shift_window,               -- nullable for DAY reports
    period_label                VARCHAR(100),
    generated_by_user_id        BIGINT                      NOT NULL,
    generated_at                TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    notes                       TEXT,
    total_expected_revenue      NUMERIC(14,2),
    total_cash_collected        NUMERIC(14,2),
    total_upi_collected         NUMERIC(14,2),
    total_card_collected        NUMERIC(14,2),
    total_fleet_card_collected  NUMERIC(14,2),
    total_credit_sold           NUMERIC(14,2),
    total_credit_recovered      NUMERIC(14,2),
    cash_discrepancy            NUMERIC(14,2),
    total_litres_sold           NUMERIC(14,3),
    total_litres_delivered      NUMERIC(14,3),
    total_cost_of_goods         NUMERIC(14,2),
    total_gross_profit          NUMERIC(14,2),
    total_dip_loss_amount       NUMERIC(14,2),

    CONSTRAINT fk_bs_pump           FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_bs_generated_by   FOREIGN KEY (generated_by_user_id) REFERENCES users (id)
);

-- -----------------------------------------------------------------------------
-- bs_fuel_lines
-- Per-fuel-type breakdown lines within a balance sheet.
-- Deleted automatically when the parent balance sheet is deleted.
-- -----------------------------------------------------------------------------
CREATE TABLE bs_fuel_lines (
    id                  BIGSERIAL       PRIMARY KEY,
    balance_sheet_id    BIGINT          NOT NULL,
    fuel_type           fuel_type       NOT NULL,
    opening_stock       NUMERIC(14,3)   NOT NULL DEFAULT 0,
    closing_stock       NUMERIC(14,3)   NOT NULL DEFAULT 0,
    delivered_litres    NUMERIC(14,3)   NOT NULL DEFAULT 0,
    delivered_cost      NUMERIC(14,2)   NOT NULL DEFAULT 0,
    sold_litres         NUMERIC(14,3)   NOT NULL DEFAULT 0,
    selling_price       NUMERIC(10,3)   NOT NULL DEFAULT 0,
    expected_revenue    NUMERIC(14,2)   NOT NULL DEFAULT 0,
    cost_of_goods       NUMERIC(14,2)   NOT NULL DEFAULT 0,
    gross_profit        NUMERIC(14,2)   NOT NULL DEFAULT 0,
    credit_sold_amount  NUMERIC(14,2)   NOT NULL DEFAULT 0,
    stock_variance      NUMERIC(14,3)   NOT NULL DEFAULT 0,
    dip_loss_litres     NUMERIC(14,3)   NOT NULL DEFAULT 0,
    dip_loss_amount     NUMERIC(14,2)   NOT NULL DEFAULT 0,

    CONSTRAINT fk_bsfl_balance_sheet    FOREIGN KEY (balance_sheet_id) REFERENCES balance_sheets (id) ON DELETE CASCADE
);

-- -----------------------------------------------------------------------------
-- bs_shift_lines
-- Per-shift breakdown lines within a balance sheet.
-- Allows a day-level report to show each shift's contribution.
-- -----------------------------------------------------------------------------
CREATE TABLE bs_shift_lines (
    id                      BIGSERIAL       PRIMARY KEY,
    balance_sheet_id        BIGINT          NOT NULL,
    shift_id                BIGINT          NOT NULL,
    operator_name           VARCHAR(150)    NOT NULL,
    nozzle_number           INTEGER         NOT NULL,
    fuel_types_summary      VARCHAR(255)    NOT NULL,
    litres_sold             NUMERIC(14,3)   NOT NULL DEFAULT 0,
    expected_revenue        NUMERIC(14,2)   NOT NULL DEFAULT 0,
    cash_collected          NUMERIC(14,2)   NOT NULL DEFAULT 0,
    upi_collected           NUMERIC(14,2)   NOT NULL DEFAULT 0,
    card_collected          NUMERIC(14,2)   NOT NULL DEFAULT 0,
    fleet_card_collected    NUMERIC(14,2)   NOT NULL DEFAULT 0,
    credit_amount           NUMERIC(14,2)   NOT NULL DEFAULT 0,
    discrepancy             NUMERIC(14,2)   NOT NULL DEFAULT 0,

    CONSTRAINT fk_bssl_balance_sheet    FOREIGN KEY (balance_sheet_id) REFERENCES balance_sheets (id) ON DELETE CASCADE,
    CONSTRAINT fk_bssl_shift            FOREIGN KEY (shift_id) REFERENCES shifts (id)
);


-- =============================================================================
-- SECTION 7: OPERATIONS
-- =============================================================================

-- -----------------------------------------------------------------------------
-- pump_expenses
-- Operational expenditures at a pump (maintenance, salaries, utilities, etc.).
-- approval_status is driven by pump_locations.expense_approval_threshold:
--   - If amount <= threshold OR threshold IS NULL, auto-approved.
--   - Otherwise, PENDING_APPROVAL until a manager approves.
-- -----------------------------------------------------------------------------
CREATE TABLE pump_expenses (
    id                  BIGSERIAL               PRIMARY KEY,
    pump_id             BIGINT                  NOT NULL,
    category            expense_category        NOT NULL,
    amount              NUMERIC(12,2)           NOT NULL,
    description         TEXT,
    expense_date        DATE                    NOT NULL,
    recorded_by_user_id BIGINT                  NOT NULL,
    approval_status     expense_approval_status NOT NULL DEFAULT 'APPROVED',
    approved_by_user_id BIGINT,
    approved_at         TIMESTAMPTZ,
    approval_notes      TEXT,
    created_at          TIMESTAMPTZ             NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_expense_pump          FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_expense_recorded_by   FOREIGN KEY (recorded_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_expense_approved_by   FOREIGN KEY (approved_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_expense_amount_positive  CHECK (amount > 0)
);

-- -----------------------------------------------------------------------------
-- pump_documents
-- Compliance documents (licences, certificates) with expiry tracking.
-- The status field is updated by a scheduler before expiry dates approach.
-- -----------------------------------------------------------------------------
CREATE TABLE pump_documents (
    id                  BIGSERIAL       PRIMARY KEY,
    pump_id             BIGINT          NOT NULL,
    name                VARCHAR(150)    NOT NULL,
    doc_type            VARCHAR(100)    NOT NULL,
    expiry_date         DATE,
    status              document_status NOT NULL DEFAULT 'VALID',
    notes               TEXT,
    uploaded_by_user_id BIGINT          NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_doc_pump          FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_doc_uploaded_by   FOREIGN KEY (uploaded_by_user_id) REFERENCES users (id)
);

-- -----------------------------------------------------------------------------
-- cash_events
-- Tracks cash movements at the pump level (opening balance, cash-in, cash-out,
-- closing balance). Used to reconcile the physical cash drawer.
-- Amount can be negative for CASH_OUT events.
-- -----------------------------------------------------------------------------
CREATE TABLE cash_events (
    id                  BIGSERIAL           PRIMARY KEY,
    pump_id             BIGINT              NOT NULL,
    event_type          cash_event_type     NOT NULL,
    amount              NUMERIC(12,2)       NOT NULL,
    description         TEXT,
    event_date          DATE                NOT NULL,
    recorded_by_user_id BIGINT              NOT NULL,
    created_at          TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_cash_event_pump           FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_cash_event_recorded_by    FOREIGN KEY (recorded_by_user_id) REFERENCES users (id)
);

-- -----------------------------------------------------------------------------
-- nozzle_calibration_logs
-- Calibration records for nozzles. next_calibration_due is null when no follow-up
-- is scheduled. Alerts fire when next_calibration_due is approaching.
-- -----------------------------------------------------------------------------
CREATE TABLE nozzle_calibration_logs (
    id                      BIGSERIAL       PRIMARY KEY,
    pump_id                 BIGINT          NOT NULL,
    nozzle_id               BIGINT          NOT NULL,
    calibration_date        DATE            NOT NULL,
    next_calibration_due    DATE,
    calibrated_by           VARCHAR(150),
    certificate_reference   VARCHAR(100),
    notes                   TEXT,
    logged_by_user_id       BIGINT          NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_cal_pump          FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_cal_nozzle        FOREIGN KEY (nozzle_id) REFERENCES nozzles (id),
    CONSTRAINT fk_cal_logged_by     FOREIGN KEY (logged_by_user_id) REFERENCES users (id)
);

-- -----------------------------------------------------------------------------
-- nozzle_reading_adjustments
-- Manual corrections to a nozzle outlet's cumulative meter reading.
-- Examples: nozzle replacement, counter rollover correction.
-- Full audit trail: previous and new readings, reason, and who made the change.
-- -----------------------------------------------------------------------------
CREATE TABLE nozzle_reading_adjustments (
    id                      BIGSERIAL       PRIMARY KEY,
    pump_id                 BIGINT          NOT NULL,
    nozzle_id               BIGINT          NOT NULL,
    outlet_id               BIGINT          NOT NULL,
    adjustment_type         VARCHAR(50)     NOT NULL,
    fuel_type               VARCHAR(30)     NOT NULL,
    previous_reading        NUMERIC(12,3)   NOT NULL,
    new_reading             NUMERIC(12,3)   NOT NULL,
    reason                  TEXT            NOT NULL,
    recorded_by_user_id     BIGINT          NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_nra_pump          FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_nra_nozzle        FOREIGN KEY (nozzle_id) REFERENCES nozzles (id),
    CONSTRAINT fk_nra_outlet        FOREIGN KEY (outlet_id) REFERENCES nozzle_outlets (id),
    CONSTRAINT fk_nra_recorded_by   FOREIGN KEY (recorded_by_user_id) REFERENCES users (id)
);

-- -----------------------------------------------------------------------------
-- fuel_dip_entries
-- Stock-level corrections resulting from a dip check. These create a corresponding
-- inventory_lots entry (is_dip_adjustment = TRUE) to keep FIFO accounting intact.
-- dip_check_id links back to the dip reading that triggered this correction.
-- -----------------------------------------------------------------------------
CREATE TABLE fuel_dip_entries (
    id                  BIGSERIAL       PRIMARY KEY,
    pump_id             BIGINT          NOT NULL,
    fuel_type           VARCHAR(30)     NOT NULL,
    litres_removed      NUMERIC(12,3)   NOT NULL,
    price_per_unit      NUMERIC(10,3)   NOT NULL,
    monetary_loss       NUMERIC(14,2)   NOT NULL,
    reason              TEXT            NOT NULL,
    dip_date            DATE            NOT NULL,
    recorded_by_user_id BIGINT          NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_fde_pump          FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_fde_recorded_by   FOREIGN KEY (recorded_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_fde_litres_positive  CHECK (litres_removed > 0)
);


-- =============================================================================
-- SECTION 8: NOTIFICATIONS & AUDIT
-- =============================================================================

-- -----------------------------------------------------------------------------
-- notifications
-- In-app alerts generated by the system (low stock, overdue shifts, etc.).
-- dedup_key prevents duplicate notifications for the same event.
-- read_at is null until the user views it.
-- -----------------------------------------------------------------------------
CREATE TABLE notifications (
    id          BIGSERIAL           PRIMARY KEY,
    pump_id     BIGINT              NOT NULL,
    type        notification_type   NOT NULL,
    title       VARCHAR(200)        NOT NULL,
    message     TEXT                NOT NULL,
    dedup_key   VARCHAR(300)        NOT NULL,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_notif_pump            FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT uq_notif_pump_dedup      UNIQUE (pump_id, dedup_key)
);

-- -----------------------------------------------------------------------------
-- audit_logs
-- Immutable record of every significant action in the system.
-- pump_id is nullable for platform-level actions (LOGIN, LOGIN_FAILED).
-- actor_name is snapshotted at write time so renames don't break audit history.
-- -----------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id              BIGSERIAL       PRIMARY KEY,
    pump_id         BIGINT,         -- nullable for platform-level events
    action          audit_action    NOT NULL,
    entity_type     VARCHAR(100),
    entity_id       VARCHAR(100),
    description     TEXT,
    actor_id        BIGINT,         -- nullable for LOGIN_FAILED (no authenticated user)
    actor_name      VARCHAR(150)    NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_audit_actor   FOREIGN KEY (actor_id) REFERENCES users (id)
    -- pump_id intentionally has no FK: platform-level rows have pump_id = NULL
);


-- =============================================================================
-- SECTION 9: STAFF & PAYROLL
-- =============================================================================

-- -----------------------------------------------------------------------------
-- payroll_records
-- Monthly/periodic payroll summaries per employee. Status moves DRAFT -> APPROVED
-- -> PAID. gross_amount is calculated from daily_rate * days_worked + adjustments.
-- -----------------------------------------------------------------------------
CREATE TABLE payroll_records (
    id              BIGSERIAL       PRIMARY KEY,
    pump_id         BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    period_from     DATE            NOT NULL,
    period_to       DATE            NOT NULL,
    days_worked     INTEGER         NOT NULL DEFAULT 0,
    gross_amount    NUMERIC(12,2)   NOT NULL DEFAULT 0,
    notes           TEXT,
    status          payroll_status  NOT NULL DEFAULT 'DRAFT',
    approved_by     BIGINT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_payroll_pump             FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_payroll_user             FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_payroll_approved_by      FOREIGN KEY (approved_by) REFERENCES users (id),
    CONSTRAINT uq_payroll_pump_user_period UNIQUE (pump_id, user_id, period_from, period_to)
);

-- -----------------------------------------------------------------------------
-- staff_preference
-- Stores each staff member's preferred shift and day off.
-- Used by the shift planning algorithm as soft constraints.
-- One preference record per user per pump.
-- -----------------------------------------------------------------------------
CREATE TABLE staff_preference (
    id              BIGSERIAL       PRIMARY KEY,
    pump_id         BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    preferred_shift shift_window    NOT NULL DEFAULT 'SHIFT_1',
    preferred_day_off VARCHAR(20),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_sp_pump   FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_sp_user   FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_sp_pump_user  UNIQUE (pump_id, user_id)
);

-- -----------------------------------------------------------------------------
-- staff_leave
-- Records individual leave days per staff member.
-- Checked during shift planning so absent staff are not scheduled.
-- UNIQUE(pump_id, user_id, leave_date) prevents duplicate leave entries.
-- -----------------------------------------------------------------------------
CREATE TABLE staff_leave (
    id          BIGSERIAL       PRIMARY KEY,
    pump_id     BIGINT          NOT NULL,
    user_id     BIGINT          NOT NULL,
    leave_date  DATE            NOT NULL,
    reason      TEXT,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_sl_pump   FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_sl_user   FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_sl_pump_user_date     UNIQUE (pump_id, user_id, leave_date)
);

-- -----------------------------------------------------------------------------
-- shift_plan
-- A weekly/monthly schedule generated for a pump. status progresses DRAFT -> PUBLISHED.
-- Entries within this plan are stored in shift_plan_entry.
-- -----------------------------------------------------------------------------
CREATE TABLE shift_plan (
    id                          BIGSERIAL       PRIMARY KEY,
    pump_id                     BIGINT          NOT NULL,
    week_start                  DATE            NOT NULL,
    status                      VARCHAR(30)     NOT NULL DEFAULT 'DRAFT',
    operators_per_day_shift     INTEGER         NOT NULL DEFAULT 1,
    operators_per_night_shift   INTEGER         NOT NULL DEFAULT 1,
    created_by_user_id          BIGINT,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_splan_pump        FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_splan_created_by  FOREIGN KEY (created_by_user_id) REFERENCES users (id)
);

-- -----------------------------------------------------------------------------
-- shift_plan_entry
-- Individual assignments within a shift plan: which operator covers which shift
-- on which date. status = PLANNED until actual shift is opened (then FULFILLED).
-- -----------------------------------------------------------------------------
CREATE TABLE shift_plan_entry (
    id                  BIGSERIAL       PRIMARY KEY,
    shift_plan_id       BIGINT          NOT NULL,
    shift_date          DATE            NOT NULL,
    shift_window        shift_window    NOT NULL,
    operator_user_id    BIGINT          NOT NULL,
    status              VARCHAR(30)     NOT NULL DEFAULT 'PLANNED',
    note                VARCHAR(200),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_spe_plan  FOREIGN KEY (shift_plan_id) REFERENCES shift_plan (id) ON DELETE CASCADE,
    CONSTRAINT fk_spe_user  FOREIGN KEY (operator_user_id) REFERENCES users (id)
);


-- =============================================================================
-- SECTION 10: AUTH & SECURITY
-- =============================================================================

-- -----------------------------------------------------------------------------
-- password_reset_tokens
-- One-time tokens sent to users to reset their password.
-- Tokens are UUIDs; used_at is set when the token is consumed.
-- Expired and used tokens should be purged periodically.
-- -----------------------------------------------------------------------------
CREATE TABLE password_reset_tokens (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     BIGINT          NOT NULL,
    token       UUID            NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    expires_at  TIMESTAMPTZ     NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_prt_user  FOREIGN KEY (user_id) REFERENCES users (id)
);

-- -----------------------------------------------------------------------------
-- token_blacklist
-- Stores hashes (SHA-256 hex) of invalidated JWTs (logout, forced revocation).
-- token_hash instead of raw token to avoid storing sensitive credentials.
-- expires_at mirrors the JWT's original expiry — enables safe cleanup of old rows.
-- -----------------------------------------------------------------------------
CREATE TABLE token_blacklist (
    id              BIGSERIAL       PRIMARY KEY,
    token_hash      VARCHAR(64)     NOT NULL UNIQUE,  -- SHA-256 hex of the JWT
    user_id         BIGINT          NOT NULL,
    expires_at      TIMESTAMPTZ     NOT NULL,
    blacklisted_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_tbl_user  FOREIGN KEY (user_id) REFERENCES users (id)
);

-- -----------------------------------------------------------------------------
-- login_attempts
-- Tracks all login attempts (success and failure) per phone number and IP.
-- Used for rate-limiting and brute-force detection. Never stores passwords.
-- -----------------------------------------------------------------------------
CREATE TABLE login_attempts (
    id              BIGSERIAL       PRIMARY KEY,
    phone_number    VARCHAR(15)     NOT NULL,
    ip_address      VARCHAR(45),
    success         BOOLEAN         NOT NULL,
    attempted_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);


-- =============================================================================
-- SECTION 11: PUMP CONFIGURATION
-- =============================================================================

-- -----------------------------------------------------------------------------
-- pump_shift_configs
-- Per-pump shift window configuration. Overrides the global SHIFT_1/2/3 labels
-- with pump-specific hours and display names (e.g. "Morning 6am-2pm").
-- start_hour: 0-23 (24h format), end_hour: 1-24 (24 = midnight next day).
-- -----------------------------------------------------------------------------
CREATE TABLE pump_shift_configs (
    id              BIGSERIAL       PRIMARY KEY,
    pump_id         BIGINT          NOT NULL,
    window_label    VARCHAR(30)     NOT NULL,   -- matches shift_window enum values
    start_hour      INTEGER         NOT NULL,
    end_hour        INTEGER         NOT NULL,
    display_name    VARCHAR(50)     NOT NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    sort_order      INTEGER         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_psc_pump          FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT uq_psc_pump_label    UNIQUE (pump_id, window_label),
    CONSTRAINT chk_psc_start_hour   CHECK (start_hour >= 0 AND start_hour <= 23),
    CONSTRAINT chk_psc_end_hour     CHECK (end_hour >= 1 AND end_hour <= 24)
);

-- -----------------------------------------------------------------------------
-- pump_closures
-- Records days when a pump is fully closed (holiday, maintenance, etc.).
-- Prevents shift creation and triggers special handling in reports.
-- UNIQUE(pump_id, closure_date) ensures one closure record per day per pump.
-- -----------------------------------------------------------------------------
CREATE TABLE pump_closures (
    id                  BIGSERIAL       PRIMARY KEY,
    pump_id             BIGINT          NOT NULL,
    closure_date        DATE            NOT NULL,
    reason              VARCHAR(255)    NOT NULL,
    created_by_user_id  BIGINT          NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_pc_pump       FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_pc_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id),
    CONSTRAINT uq_pc_pump_date  UNIQUE (pump_id, closure_date)
);

-- -----------------------------------------------------------------------------
-- shift_handovers
-- Formal handover record between outgoing and incoming operators.
-- Captures whether cash and meter readings were physically verified at handover.
-- -----------------------------------------------------------------------------
CREATE TABLE shift_handovers (
    id                      BIGSERIAL       PRIMARY KEY,
    pump_id                 BIGINT          NOT NULL,
    outgoing_shift_id       BIGINT          NOT NULL,
    outgoing_operator_id    BIGINT          NOT NULL,
    incoming_operator_id    BIGINT          NOT NULL,
    physical_cash_verified  BOOLEAN         NOT NULL DEFAULT FALSE,
    meter_readings_verified BOOLEAN         NOT NULL DEFAULT FALSE,
    notes                   TEXT,
    handover_time           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_sho_pump              FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_sho_outgoing_shift    FOREIGN KEY (outgoing_shift_id) REFERENCES shifts (id),
    CONSTRAINT fk_sho_outgoing_op       FOREIGN KEY (outgoing_operator_id) REFERENCES users (id),
    CONSTRAINT fk_sho_incoming_op       FOREIGN KEY (incoming_operator_id) REFERENCES users (id)
);


-- =============================================================================
-- SECTION 12: ANCILLARY PRODUCTS
-- Note: ancillary_sales is created before ancillary_lot_consumptions to resolve
-- the forward reference in the FK on ancillary_lot_consumptions.sale_id.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- ancillary_products
-- Non-fuel products sold at the pump (engine oil, coolant, etc.).
-- current_stock_units tracks unit count. low_stock_threshold triggers alerts.
-- gst_rate_percent is per-product for correct tax calculation at point of sale.
-- -----------------------------------------------------------------------------
CREATE TABLE ancillary_products (
    id                      BIGSERIAL                   PRIMARY KEY,
    pump_id                 BIGINT                      NOT NULL,
    name                    VARCHAR(100)                NOT NULL,
    brand                   VARCHAR(100),
    variant                 VARCHAR(100),
    package_size            NUMERIC(10,3)               NOT NULL,
    unit_of_measure         unit_of_measure             NOT NULL,
    current_stock_units     INTEGER                     NOT NULL DEFAULT 0,
    low_stock_threshold     INTEGER,
    hsn_code                VARCHAR(10),
    gst_rate_percent        NUMERIC(5,2)                NOT NULL DEFAULT 18.00,
    status                  ancillary_product_status    NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_ap_pump               FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT chk_ap_package_positive  CHECK (package_size > 0),
    CONSTRAINT chk_ap_stock_non_neg     CHECK (current_stock_units >= 0),
    CONSTRAINT chk_ap_gst_non_neg       CHECK (gst_rate_percent >= 0)
);

-- -----------------------------------------------------------------------------
-- ancillary_product_prices
-- Price history for ancillary products (same pattern as global_fuel_prices).
-- Active price = latest effective_from for a given product_id.
-- -----------------------------------------------------------------------------
CREATE TABLE ancillary_product_prices (
    id                  BIGSERIAL       PRIMARY KEY,
    product_id          BIGINT          NOT NULL,
    pump_id             BIGINT          NOT NULL,
    price_per_unit      NUMERIC(12,2)   NOT NULL,
    effective_from      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    set_by_user_id      BIGINT          NOT NULL,

    CONSTRAINT fk_app_product       FOREIGN KEY (product_id) REFERENCES ancillary_products (id),
    CONSTRAINT fk_app_pump          FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_app_set_by        FOREIGN KEY (set_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_app_price_positive   CHECK (price_per_unit > 0)
);

-- -----------------------------------------------------------------------------
-- ancillary_stock_deliveries
-- Records each delivery of ancillary products to a pump.
-- quantity_units is integer (whole packs/units, not litres).
-- -----------------------------------------------------------------------------
CREATE TABLE ancillary_stock_deliveries (
    id                      BIGSERIAL       PRIMARY KEY,
    product_id              BIGINT          NOT NULL,
    pump_id                 BIGINT          NOT NULL,
    quantity_units          INTEGER         NOT NULL,
    cost_price_per_unit     NUMERIC(12,2)   NOT NULL,
    delivery_date           DATE            NOT NULL,
    invoice_reference       VARCHAR(100),
    notes                   TEXT,
    logged_by_user_id       BIGINT          NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_asd_product       FOREIGN KEY (product_id) REFERENCES ancillary_products (id),
    CONSTRAINT fk_asd_pump          FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_asd_logged_by     FOREIGN KEY (logged_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_asd_qty_positive     CHECK (quantity_units > 0),
    CONSTRAINT chk_asd_cost_positive    CHECK (cost_price_per_unit > 0)
);

-- -----------------------------------------------------------------------------
-- ancillary_stock_lots
-- FIFO inventory lots for ancillary products (mirrors inventory_lots for fuel).
-- remaining_quantity tracks how many units are left; status -> EXHAUSTED at 0.
-- -----------------------------------------------------------------------------
CREATE TABLE ancillary_stock_lots (
    id                      BIGSERIAL           PRIMARY KEY,
    delivery_id             BIGINT              NOT NULL,
    product_id              BIGINT              NOT NULL,
    pump_id                 BIGINT              NOT NULL,
    original_quantity       INTEGER             NOT NULL,
    remaining_quantity      INTEGER             NOT NULL,
    cost_price_per_unit     NUMERIC(12,2)       NOT NULL,
    delivery_date           DATE                NOT NULL,
    status                  ancillary_lot_status NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_asl_delivery  FOREIGN KEY (delivery_id) REFERENCES ancillary_stock_deliveries (id),
    CONSTRAINT fk_asl_product   FOREIGN KEY (product_id) REFERENCES ancillary_products (id),
    CONSTRAINT fk_asl_pump      FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT chk_asl_remaining_non_neg    CHECK (remaining_quantity >= 0)
);

-- -----------------------------------------------------------------------------
-- ancillary_sales
-- Defined BEFORE ancillary_lot_consumptions to allow the FK in that table.
-- Records each point-of-sale transaction for ancillary products.
-- gst_amount and total_with_gst are computed and stored (not derived on read).
-- client_id is nullable — walk-in customers don't need a credit account.
-- -----------------------------------------------------------------------------
CREATE TABLE ancillary_sales (
    id                      BIGSERIAL                   PRIMARY KEY,
    pump_id                 BIGINT                      NOT NULL,
    product_id              BIGINT                      NOT NULL,
    quantity_units          INTEGER                     NOT NULL,
    selling_price_per_unit  NUMERIC(12,2)               NOT NULL,
    total_amount            NUMERIC(14,2)               NOT NULL,
    gst_amount              NUMERIC(12,2)               NOT NULL DEFAULT 0,
    total_with_gst          NUMERIC(14,2)               NOT NULL,
    payment_mode            transaction_payment_mode    NOT NULL DEFAULT 'CASH',
    client_id               BIGINT,
    client_name             VARCHAR(150),
    bill_no                 VARCHAR(50),
    notes                   TEXT,
    sold_by_user_id         BIGINT                      NOT NULL,
    sale_date               DATE                        NOT NULL,
    created_at              TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_asale_pump        FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_asale_product     FOREIGN KEY (product_id) REFERENCES ancillary_products (id),
    CONSTRAINT fk_asale_client      FOREIGN KEY (client_id) REFERENCES credit_clients (id),
    CONSTRAINT fk_asale_sold_by     FOREIGN KEY (sold_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_asale_qty_positive   CHECK (quantity_units > 0)
);

-- -----------------------------------------------------------------------------
-- ancillary_lot_consumptions
-- Tracks FIFO lot depletion for each ancillary sale.
-- sale_id FK is now safe since ancillary_sales is already defined above.
-- -----------------------------------------------------------------------------
CREATE TABLE ancillary_lot_consumptions (
    id                      BIGSERIAL       PRIMARY KEY,
    lot_id                  BIGINT          NOT NULL,
    sale_id                 BIGINT          NOT NULL,
    quantity_consumed        INTEGER         NOT NULL,
    cost_price_per_unit     NUMERIC(12,2)   NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_alc_lot   FOREIGN KEY (lot_id) REFERENCES ancillary_stock_lots (id),
    CONSTRAINT fk_alc_sale  FOREIGN KEY (sale_id) REFERENCES ancillary_sales (id)
);


-- =============================================================================
-- SECTION 13: VENDOR PAYMENTS
-- =============================================================================

-- -----------------------------------------------------------------------------
-- fuel_supplier_payments
-- Records payments made to fuel suppliers. delivery_id is nullable because
-- payments may be advance payments or partial payments not tied to one delivery.
-- -----------------------------------------------------------------------------
CREATE TABLE fuel_supplier_payments (
    id                  BIGSERIAL       PRIMARY KEY,
    pump_id             BIGINT          NOT NULL,
    supplier_id         BIGINT          NOT NULL,
    delivery_id         BIGINT,         -- nullable: advance or partial payments
    amount              NUMERIC(14,2)   NOT NULL,
    payment_date        DATE            NOT NULL,
    payment_mode        payment_mode    NOT NULL DEFAULT 'BANK_TRANSFER',
    reference_no        VARCHAR(100),
    notes               TEXT,
    recorded_by_user_id BIGINT          NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_fsp_pump          FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_fsp_supplier      FOREIGN KEY (supplier_id) REFERENCES fuel_suppliers (id),
    CONSTRAINT fk_fsp_delivery      FOREIGN KEY (delivery_id) REFERENCES tanker_deliveries (id),
    CONSTRAINT fk_fsp_recorded_by   FOREIGN KEY (recorded_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_fsp_amount_positive  CHECK (amount > 0)
);


-- =============================================================================
-- SECTION 14: BANK RECONCILIATION
-- =============================================================================

-- -----------------------------------------------------------------------------
-- bank_statement_imports
-- Metadata header for each bank statement file imported.
-- matched_lines counter is updated as lines are reconciled.
-- -----------------------------------------------------------------------------
CREATE TABLE bank_statement_imports (
    id                      BIGSERIAL       PRIMARY KEY,
    pump_id                 BIGINT          NOT NULL,
    bank_name               VARCHAR(100)    NOT NULL,
    account_number          VARCHAR(50),
    statement_from_date     DATE            NOT NULL,
    statement_to_date       DATE            NOT NULL,
    total_lines             INTEGER         NOT NULL DEFAULT 0,
    matched_lines           INTEGER         NOT NULL DEFAULT 0,
    imported_by_user_id     BIGINT          NOT NULL,
    imported_at             TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_bsi_pump          FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_bsi_imported_by   FOREIGN KEY (imported_by_user_id) REFERENCES users (id)
);

-- -----------------------------------------------------------------------------
-- bank_statement_lines
-- Individual transactions from a bank statement. Each line can be matched
-- to a shift (UPI collection), ancillary sale, or credit payment.
-- utr_reference is the bank's unique transaction reference for UPI/NEFT/RTGS.
-- match_status moves UNMATCHED -> MATCHED or IGNORED after reconciliation.
-- -----------------------------------------------------------------------------
CREATE TABLE bank_statement_lines (
    id                          BIGSERIAL               PRIMARY KEY,
    import_id                   BIGINT                  NOT NULL,
    txn_date                    DATE                    NOT NULL,
    narration                   TEXT,
    debit_amount                NUMERIC(14,2)           NOT NULL DEFAULT 0,
    credit_amount               NUMERIC(14,2)           NOT NULL DEFAULT 0,
    balance                     NUMERIC(14,2),
    utr_reference               VARCHAR(100),
    match_status                bank_line_match_status  NOT NULL DEFAULT 'UNMATCHED',
    matched_shift_id            BIGINT,
    matched_ancillary_sale_id   BIGINT,
    matched_payment_id          BIGINT,
    match_notes                 TEXT,
    created_at                  TIMESTAMPTZ             NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_bsl_import                FOREIGN KEY (import_id) REFERENCES bank_statement_imports (id) ON DELETE CASCADE,
    CONSTRAINT fk_bsl_shift                 FOREIGN KEY (matched_shift_id) REFERENCES shifts (id),
    CONSTRAINT fk_bsl_ancillary_sale        FOREIGN KEY (matched_ancillary_sale_id) REFERENCES ancillary_sales (id),
    CONSTRAINT fk_bsl_payment               FOREIGN KEY (matched_payment_id) REFERENCES credit_payments (id)
);


-- =============================================================================
-- SECTION 15: ASYNC JOBS
-- =============================================================================

-- -----------------------------------------------------------------------------
-- async_jobs
-- Queue table for background jobs (e.g. balance sheet generation).
-- payload_json is the input; result_json is the output on completion.
-- status transitions: QUEUED -> PROCESSING -> DONE | FAILED.
-- -----------------------------------------------------------------------------
CREATE TABLE async_jobs (
    id                  BIGSERIAL           PRIMARY KEY,
    pump_id             BIGINT              NOT NULL,
    job_type            VARCHAR(100)        NOT NULL,
    status              async_job_status    NOT NULL DEFAULT 'QUEUED',
    payload_json        JSONB,
    result_json         JSONB,
    error_message       TEXT,
    created_by_user_id  BIGINT              NOT NULL,
    created_at          TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,

    CONSTRAINT fk_job_pump          FOREIGN KEY (pump_id) REFERENCES pump_locations (id),
    CONSTRAINT fk_job_created_by    FOREIGN KEY (created_by_user_id) REFERENCES users (id)
);


-- =============================================================================
-- SECTION 16: NOTIFICATION DISPATCH LOG
-- =============================================================================

-- -----------------------------------------------------------------------------
-- notification_dispatch_log
-- Records each outbound notification (SMS, WhatsApp, email, in-app).
-- provider_reference is the external provider's message ID for delivery tracking.
-- This is separate from the notifications table which is for in-app only.
-- -----------------------------------------------------------------------------
CREATE TABLE notification_dispatch_log (
    id                  BIGSERIAL           PRIMARY KEY,
    pump_id             BIGINT              NOT NULL,
    notification_type   notification_type   NOT NULL,
    channel             VARCHAR(30)         NOT NULL,   -- IN_APP, SMS, WHATSAPP, EMAIL
    recipient           VARCHAR(200)        NOT NULL,   -- phone number or email address
    message             TEXT                NOT NULL,
    status              VARCHAR(30)         NOT NULL DEFAULT 'SENT',
    provider_reference  VARCHAR(200),
    sent_at             TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_ndl_pump  FOREIGN KEY (pump_id) REFERENCES pump_locations (id)
);


-- =============================================================================
-- SECTION 17: SHEDLOCK (Distributed Scheduler Coordination)
-- Used by Spring ShedLock to prevent concurrent scheduler execution across pods.
-- lock_until: when the lock expires; locked_at: when it was acquired;
-- locked_by: hostname/pod identifier of the instance holding the lock.
-- =============================================================================

CREATE TABLE shedlock (
    name        VARCHAR(64)     NOT NULL,
    lock_until  TIMESTAMPTZ     NOT NULL,
    locked_at   TIMESTAMPTZ     NOT NULL,
    locked_by   VARCHAR(255)    NOT NULL,

    CONSTRAINT pk_shedlock  PRIMARY KEY (name)
);


-- =============================================================================
-- INDEXES
-- Organised by table. FK indexes prevent full sequential scans on joins.
-- Partial indexes reduce index size and improve query performance for filtered queries.
-- =============================================================================

-- users
CREATE INDEX idx_users_phone  ON users (phone_number);
CREATE INDEX idx_users_role   ON users (role);
CREATE INDEX idx_users_status ON users (status);

-- pump_locations
CREATE INDEX idx_pumps_owner ON pump_locations (owner_id);

-- underground_tanks
CREATE INDEX idx_tanks_pump      ON underground_tanks (pump_id);
CREATE INDEX idx_tanks_pump_fuel ON underground_tanks (pump_id, fuel_type);

-- nozzles
CREATE INDEX idx_nozzles_pump ON nozzles (pump_id, status);

-- nozzle_outlets
CREATE INDEX idx_outlets_nozzle ON nozzle_outlets (nozzle_id);
CREATE INDEX idx_outlets_tank   ON nozzle_outlets (tank_id);

-- global_fuel_prices (DESC on effective_from so latest price lookup is fast)
CREATE INDEX idx_prices_pump_fuel ON global_fuel_prices (pump_id, fuel_type, effective_from DESC);

-- tanker_deliveries
CREATE INDEX idx_deliveries_pump ON tanker_deliveries (pump_id);
CREATE INDEX idx_deliveries_tank ON tanker_deliveries (tank_id);

-- inventory_lots
CREATE INDEX idx_lots_pump_fuel ON inventory_lots (pump_id, fuel_type, status);
CREATE INDEX idx_lots_tank      ON inventory_lots (tank_id);
-- Partial index: only active lots — used in FIFO queries
CREATE INDEX idx_lots_active ON inventory_lots (pump_id, fuel_type)
    WHERE status = 'ACTIVE';

-- lot_consumptions
CREATE INDEX idx_consumptions_lot   ON lot_consumptions (lot_id);
CREATE INDEX idx_consumptions_shift ON lot_consumptions (shift_id);

-- dip_checks
CREATE INDEX idx_dips_pump ON dip_checks (pump_id);
CREATE INDEX idx_dips_tank ON dip_checks (tank_id);

-- shifts
CREATE INDEX idx_shifts_pump     ON shifts (pump_id);
CREATE INDEX idx_shifts_operator ON shifts (operator_id);
CREATE INDEX idx_shifts_nozzle   ON shifts (nozzle_id);
CREATE INDEX idx_shifts_date     ON shifts (pump_id, shift_date);
-- Critical partial unique index: prevents two open shifts on the same nozzle.
-- Only rows with status OPEN or OPEN_OVERDUE are included — closed shifts are excluded.
CREATE UNIQUE INDEX idx_shifts_nozzle_open ON shifts (nozzle_id)
    WHERE status IN ('OPEN', 'OPEN_OVERDUE');

-- shift_fuel_readings
CREATE INDEX idx_sfr_shift  ON shift_fuel_readings (shift_id);
CREATE INDEX idx_sfr_outlet ON shift_fuel_readings (outlet_id);

-- fuel_transactions
CREATE INDEX idx_ftxn_shift ON fuel_transactions (shift_id);
CREATE INDEX idx_ftxn_pump  ON fuel_transactions (pump_id);

-- shift_credit_entries
CREATE INDEX idx_sce_shift  ON shift_credit_entries (shift_id);
CREATE INDEX idx_sce_client ON shift_credit_entries (client_id);

-- credit_clients
CREATE INDEX idx_cc_pump   ON credit_clients (pump_id);
CREATE INDEX idx_cc_parent ON credit_clients (parent_client_id);

-- credit_payments
CREATE INDEX idx_cpay_client ON credit_payments (client_id);
CREATE INDEX idx_cpay_pump   ON credit_payments (pump_id);

-- credit_interest_charges
CREATE INDEX idx_cic_client ON credit_interest_charges (client_id);
CREATE INDEX idx_cic_pump   ON credit_interest_charges (pump_id);

-- notifications
CREATE INDEX idx_notif_pump ON notifications (pump_id, read_at);

-- audit_logs (DESC on created_at for most-recent-first queries)
CREATE INDEX idx_audit_pump ON audit_logs (pump_id, created_at DESC);

-- login_attempts (DESC for most-recent-first rate-limit window queries)
CREATE INDEX idx_login_phone_time ON login_attempts (phone_number, attempted_at DESC);
CREATE INDEX idx_login_ip_time    ON login_attempts (ip_address, attempted_at DESC);

-- token_blacklist
CREATE INDEX idx_blacklist_hash   ON token_blacklist (token_hash);
CREATE INDEX idx_blacklist_expiry ON token_blacklist (expires_at);  -- For cleanup jobs

-- pump_shift_configs
CREATE INDEX idx_psc_pump ON pump_shift_configs (pump_id);

-- pump_closures
CREATE INDEX idx_closures_pump_date ON pump_closures (pump_id, closure_date);

-- shift_handovers
CREATE INDEX idx_handovers_pump  ON shift_handovers (pump_id);
CREATE INDEX idx_handovers_shift ON shift_handovers (outgoing_shift_id);

-- ancillary_products
CREATE INDEX idx_anc_products_pump ON ancillary_products (pump_id);

-- ancillary_product_prices (DESC on effective_from for active price lookup)
CREATE INDEX idx_anc_prices_product ON ancillary_product_prices (product_id, effective_from DESC);

-- ancillary_stock_lots
CREATE INDEX idx_anc_lots_product ON ancillary_stock_lots (product_id, status);
-- Partial index: only active lots — used in FIFO queries for ancillary stock
CREATE INDEX idx_anc_lots_active ON ancillary_stock_lots (product_id)
    WHERE status = 'ACTIVE';

-- ancillary_sales (DESC on sale_date for recent-first dashboard queries)
CREATE INDEX idx_anc_sales_pump    ON ancillary_sales (pump_id, sale_date DESC);
CREATE INDEX idx_anc_sales_product ON ancillary_sales (product_id);

-- fuel_supplier_payments
CREATE INDEX idx_fsp_pump     ON fuel_supplier_payments (pump_id);
CREATE INDEX idx_fsp_supplier ON fuel_supplier_payments (supplier_id);
CREATE INDEX idx_fsp_delivery ON fuel_supplier_payments (delivery_id);

-- bank_statement_imports
CREATE INDEX idx_bsi_pump ON bank_statement_imports (pump_id);

-- bank_statement_lines
CREATE INDEX idx_bsl_import ON bank_statement_lines (import_id);
CREATE INDEX idx_bsl_status ON bank_statement_lines (import_id, match_status);
-- Partial index on utr_reference for UPI reconciliation lookups
CREATE INDEX idx_bsl_utr ON bank_statement_lines (utr_reference)
    WHERE utr_reference IS NOT NULL;

-- async_jobs
CREATE INDEX idx_jobs_pump_status ON async_jobs (pump_id, status);
-- Partial index: only pending/running jobs — used by the job processor
CREATE INDEX idx_jobs_status ON async_jobs (status)
    WHERE status IN ('QUEUED', 'PROCESSING');

-- payroll_records
-- idx_payroll_pump is intentionally omitted: pump_id is the leading column of
-- uq_payroll_pump_user_period, so Postgres uses that unique index for pump-scoped queries.
CREATE INDEX idx_payroll_user ON payroll_records (user_id);

-- =============================================================================
-- ADDITIONAL INDEXES (covering query paths not addressed by FK indexes above)
--
-- Selection criteria: only tables with unbounded row growth AND queries that run
-- on every page load, every shift open/close, or every financial aggregation.
-- Tiny/bounded tables (pump_shift_configs, pump_closures, staff_preferences, etc.)
-- are intentionally omitted — PostgreSQL seq-scans those faster than index lookups
-- at their expected row counts (<100 rows per pump).
-- =============================================================================

-- users: staff listing is done on every pump dashboard and shift open.
-- Composite covers: findByAssignedPumpIdAndRoleAndStatus, findByAssignedPumpIdAndStatus.
CREATE INDEX idx_users_pump_status_role ON users (assigned_pump_id, status, role);

-- shifts: history is paginated (newest-first). The pump+status index exists but does not
-- cover the actual_start_time ordering needed for the history list and operator duty reports.
CREATE INDEX idx_shifts_pump_start_time ON shifts (pump_id, actual_start_time DESC);
CREATE INDEX idx_shifts_pump_operator_date ON shifts (pump_id, operator_id, shift_date);

-- inventory_lots: FIFO deduction by tank is called on every shift close.
-- The existing partial index covers pump+fuel_type+ACTIVE status; this adds tank-scoped FIFO.
CREATE INDEX idx_lots_tank_delivery_status ON inventory_lots (tank_id, delivery_date ASC, status);

-- nozzle_outlets: outlet-by-nozzle+fuel lookup happens on shift open and close.
CREATE INDEX idx_outlets_nozzle_fuel ON nozzle_outlets (nozzle_id, fuel_type);

-- credit_clients: client lookup and listing is on the critical path for shift credit entries.
-- Covers: findByPumpIdOrderByNameAsc, findByPumpIdAndName, findByPumpIdAndPhone.
CREATE INDEX idx_cc_pump_name  ON credit_clients (pump_id, name);
CREATE INDEX idx_cc_pump_phone ON credit_clients (pump_id, phone);

-- shift_credit_entries: summed on every credit ledger view (outstanding balance calculation).
CREATE INDEX idx_sce_client_created ON shift_credit_entries (client_id, created_at DESC);

-- credit_payments: aggregated on every ledger view to compute outstanding balance.
-- Covers: sumAmountByClientId (with approval_status filter), findPendingApprovalByPumpId.
CREATE INDEX idx_cpay_client_status     ON credit_payments (client_id, payment_approval_status);
CREATE INDEX idx_cpay_pump_status_date  ON credit_payments (pump_id, payment_approval_status, payment_date);

-- credit_interest_charges: listed on every ledger view.
CREATE INDEX idx_cic_client_created ON credit_interest_charges (client_id, created_at DESC);

-- credit_extensions: checked on every ledger load to find active limit extensions.
-- Also covers the expiration job that updates status+expiry_date.
CREATE INDEX idx_cext_pump_client_type_status ON credit_extensions (pump_id, client_id, extension_type, status);
CREATE INDEX idx_cext_status_expiry          ON credit_extensions (status, expiry_date);

-- nozzle_calibration_logs: checked on every shift open (calibration guard).
-- findLatestByNozzleId uses ORDER BY calibration_date DESC LIMIT 1.
-- findOverdueCalibrations uses next_calibration_due for the overdue alert.
CREATE INDEX idx_cal_nozzle_date ON nozzle_calibration_logs (nozzle_id, calibration_date DESC);
CREATE INDEX idx_cal_pump_due    ON nozzle_calibration_logs (pump_id, next_calibration_due);

-- pump_documents: scanned by the notification job (every 5 min) to find expiring docs.
CREATE INDEX idx_docs_pump_expiry ON pump_documents (pump_id, expiry_date);

-- fuel_transactions: paginated by pump for the transaction history list.
-- Per-shift queries are bounded and covered by idx_ftxn_shift.
CREATE INDEX idx_ftxn_pump_recorded ON fuel_transactions (pump_id, recorded_at DESC);

-- pump_expenses: paginated by pump for the expense history list.
CREATE INDEX idx_expenses_pump_date ON pump_expenses (pump_id, expense_date DESC, created_at DESC);

-- fuel_supplier_payments: listed and filtered by pump/supplier.
CREATE INDEX idx_fsp_pump_date          ON fuel_supplier_payments (pump_id, payment_date DESC);
CREATE INDEX idx_fsp_pump_supplier_date ON fuel_supplier_payments (pump_id, supplier_id, payment_date DESC);

-- bank_statement_imports: listed by pump, newest first.
-- The existing idx_bsi_pump covers pump_id lookup but not the ordering.
CREATE INDEX idx_bsi_pump_imported ON bank_statement_imports (pump_id, imported_at DESC);

-- ancillary_products: listed with status filter on every ancillary page.
CREATE INDEX idx_anc_products_pump_status_name ON ancillary_products (pump_id, status, name);

-- ancillary_stock_lots: FIFO consumption on every ancillary sale — same pattern as inventory_lots.
CREATE INDEX idx_anc_lots_product_status_delivery ON ancillary_stock_lots (product_id, status, delivery_date ASC);


-- =============================================================================
-- SEED DATA
-- Two default accounts created for initial system setup and demo purposes.
-- Passwords are bcrypt-hashed; never stored in plain text.
-- =============================================================================

-- Super Admin: full platform access, not tied to any pump.
-- Password: password
INSERT INTO users (
    employee_id,
    full_name,
    phone_number,
    password_hash,
    role,
    status
) VALUES (
    'SA001',
    'Super Admin',
    '8888888888',
    '$2a$10$WYgyMeeu7rpJksPZsTkV7.G7pE2eLiENxj8ZofDs4phy9bsvjuVAC',
    'SUPER_ADMIN',
    'ACTIVE'
);

-- Demo Owner: used for onboarding demos and local development.
-- Password: owner123
INSERT INTO users (
    employee_id,
    full_name,
    phone_number,
    password_hash,
    role,
    status
) VALUES (
    'OWN001',
    'Demo Owner',
    '9999999999',
    '$2a$10$X/uMBe5hLkiT.GJIWtJIvOA4pSZXlMuWvVCbCDy4Gm7c9fGh/sGS',
    'OWNER',
    'ACTIVE'
);
