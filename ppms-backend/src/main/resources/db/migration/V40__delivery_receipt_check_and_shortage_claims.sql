-- Receipt check of a tanker delivery: dips before and after unloading plus the litres the tank's nozzles sold
-- in between give the litres really received. Deliveries recorded before this check existed keep NULLs and
-- check_status NOT_CHECKED. Tolerances are copied from the pump at the time, so later setting changes keep history.
ALTER TABLE tanker_deliveries
    ADD COLUMN dip_before_litres          NUMERIC(12,3),
    ADD COLUMN dip_after_litres           NUMERIC(12,3),
    ADD COLUMN sold_between_litres        NUMERIC(12,3),
    ADD COLUMN received_litres            NUMERIC(12,3),
    ADD COLUMN variance_litres            NUMERIC(12,3),
    ADD COLUMN receipt_tolerance_percent  NUMERIC(5,2),
    ADD COLUMN observed_density           NUMERIC(6,1),
    ADD COLUMN invoice_density            NUMERIC(6,1),
    ADD COLUMN density_tolerance          NUMERIC(5,1),
    ADD COLUMN density_flagged            BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN check_status               VARCHAR(20)   NOT NULL DEFAULT 'NOT_CHECKED',
    ADD CONSTRAINT chk_delivery_check_status CHECK (check_status IN ('NOT_CHECKED', 'OK', 'SHORT', 'OVER')),
    ADD CONSTRAINT chk_delivery_check_fields CHECK ((check_status = 'NOT_CHECKED') = (received_litres IS NULL)
        AND (received_litres IS NULL OR (dip_before_litres IS NOT NULL AND dip_after_litres IS NOT NULL
             AND sold_between_litres IS NOT NULL AND variance_litres IS NOT NULL
             AND receipt_tolerance_percent IS NOT NULL AND density_tolerance IS NOT NULL))),
    ADD CONSTRAINT chk_delivery_received_positive CHECK (received_litres IS NULL OR received_litres > 0),
    ADD CONSTRAINT chk_delivery_dips_not_negative
        CHECK ((dip_before_litres IS NULL OR dip_before_litres >= 0) AND (dip_after_litres IS NULL OR dip_after_litres >= 0)),
    ADD CONSTRAINT chk_delivery_sold_between_not_negative CHECK (sold_between_litres IS NULL OR sold_between_litres >= 0),
    ADD CONSTRAINT chk_delivery_densities_positive
        CHECK ((observed_density IS NULL OR observed_density > 0) AND (invoice_density IS NULL OR invoice_density > 0));

-- Meter readings of each nozzle on the delivery's tank, taken with the dip before and the dip after unloading.
CREATE TABLE delivery_nozzle_readings (
    id                  BIGSERIAL       PRIMARY KEY,
    tanker_delivery_id  BIGINT          NOT NULL,
    nozzle_id           BIGINT          NOT NULL,
    reading_before      NUMERIC(14,3)   NOT NULL,
    reading_after       NUMERIC(14,3)   NOT NULL,
    litres_sold         NUMERIC(14,3)   NOT NULL,

    CONSTRAINT fk_dnr_delivery FOREIGN KEY (tanker_delivery_id) REFERENCES tanker_deliveries (id) ON DELETE CASCADE,
    CONSTRAINT fk_dnr_nozzle   FOREIGN KEY (nozzle_id)          REFERENCES nozzles (id),
    CONSTRAINT uq_dnr_delivery_nozzle UNIQUE (tanker_delivery_id, nozzle_id),
    CONSTRAINT chk_dnr_readings_not_negative CHECK (reading_before >= 0 AND reading_after >= 0),
    CONSTRAINT chk_dnr_litres_sold_not_negative CHECK (litres_sold >= 0)
);

-- One claim per short delivery; the unique delivery id makes raising it idempotent.
CREATE TABLE shortage_claims (
    id                      BIGSERIAL       PRIMARY KEY,
    pump_id                 BIGINT          NOT NULL,
    tanker_delivery_id      BIGINT          NOT NULL,
    litres_short            NUMERIC(12,3)   NOT NULL,
    price_per_litre         NUMERIC(10,3)   NOT NULL,
    claim_amount            NUMERIC(14,2)   NOT NULL,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'RAISED',
    raised_by_user_id       BIGINT          NOT NULL,
    credit_note_amount      NUMERIC(14,2),
    credit_note_reference   VARCHAR(100),
    credit_note_date        DATE,
    rejection_reason        VARCHAR(500),
    resolved_by_user_id     BIGINT,
    resolved_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_sclaim_pump        FOREIGN KEY (pump_id)             REFERENCES pump_locations (id),
    CONSTRAINT fk_sclaim_delivery    FOREIGN KEY (tanker_delivery_id)  REFERENCES tanker_deliveries (id) ON DELETE CASCADE,
    CONSTRAINT fk_sclaim_raised_by   FOREIGN KEY (raised_by_user_id)   REFERENCES users (id),
    CONSTRAINT fk_sclaim_resolved_by FOREIGN KEY (resolved_by_user_id) REFERENCES users (id),
    CONSTRAINT uq_sclaim_delivery    UNIQUE (tanker_delivery_id),
    CONSTRAINT chk_sclaim_status     CHECK (status IN ('RAISED', 'SETTLED', 'REJECTED')),
    CONSTRAINT chk_sclaim_figures    CHECK (litres_short > 0 AND price_per_litre > 0 AND claim_amount >= 0),
    CONSTRAINT chk_sclaim_settled_fields CHECK ((status = 'SETTLED') = (credit_note_amount IS NOT NULL
        AND credit_note_reference IS NOT NULL AND credit_note_date IS NOT NULL)),
    CONSTRAINT chk_sclaim_rejected_fields CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL)),
    CONSTRAINT chk_sclaim_resolved_fields CHECK ((status = 'RAISED') = (resolved_at IS NULL AND resolved_by_user_id IS NULL)),
    CONSTRAINT chk_sclaim_credit_note_positive CHECK (credit_note_amount IS NULL OR credit_note_amount > 0)
);

CREATE INDEX idx_sclaim_pump_status_created ON shortage_claims (pump_id, status, created_at DESC);

-- Short beyond this share of the invoice litres raises a claim; densities further apart than this are flagged.
ALTER TABLE pump_locations
    ADD COLUMN receipt_tolerance_percent NUMERIC(5,2) NOT NULL DEFAULT 0.20,
    ADD COLUMN density_tolerance         NUMERIC(5,1) NOT NULL DEFAULT 3.0,
    ADD CONSTRAINT chk_pump_receipt_tolerance_range CHECK (receipt_tolerance_percent > 0 AND receipt_tolerance_percent <= 5),
    ADD CONSTRAINT chk_pump_density_tolerance_range CHECK (density_tolerance > 0 AND density_tolerance <= 50);

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'DELIVERY_SHORTAGE';

ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'SHORTAGE_CLAIM_RAISED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'SHORTAGE_CLAIM_UPDATED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'SHORTAGE_CLAIM_SETTLED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'SHORTAGE_CLAIM_REJECTED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'SHORTAGE_CLAIM_WITHDRAWN';
