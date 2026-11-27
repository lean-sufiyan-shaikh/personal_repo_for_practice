-- Supplier ledger, GST input on counter-product purchases and pump test litres.

-- 1. Suppliers: an optional opening balance (what the pump owed the supplier, negative for an advance, on a date) and
--    one default supplier per pump that a tanker delivery takes when none is picked.
ALTER TABLE fuel_suppliers
    ADD COLUMN opening_balance       NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN opening_balance_date  DATE,
    ADD COLUMN is_default            BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT chk_supplier_opening_balance_dated CHECK (opening_balance = 0 OR opening_balance_date IS NOT NULL);

CREATE UNIQUE INDEX uq_supplier_one_default_per_pump ON fuel_suppliers (pump_id) WHERE is_default;

UPDATE fuel_suppliers s
SET is_default = TRUE
WHERE s.active
  AND (SELECT COUNT(*) FROM fuel_suppliers other WHERE other.pump_id = s.pump_id AND other.active) = 1;

-- 2. Tanker deliveries already carry supplier_id; a pump with exactly one supplier gets it on its older deliveries.
--    Pumps with several suppliers keep them unassigned for the owner to sort out.
UPDATE tanker_deliveries d
SET supplier_id = (SELECT s.id FROM fuel_suppliers s WHERE s.pump_id = d.pump_id)
WHERE d.supplier_id IS NULL
  AND (SELECT COUNT(*) FROM fuel_suppliers s WHERE s.pump_id = d.pump_id) = 1;

CREATE INDEX idx_delivery_pump_supplier ON tanker_deliveries (pump_id, supplier_id);

-- 3. GST input on a counter-product purchase. Either every GST field is set or none is; fuel stays outside GST.
ALTER TABLE ancillary_stock_deliveries
    ADD COLUMN gst_supplier_name    VARCHAR(150),
    ADD COLUMN gst_supplier_gstin   VARCHAR(15),
    ADD COLUMN gst_invoice_date     DATE,
    ADD COLUMN gst_taxable_value    NUMERIC(14,2),
    ADD COLUMN gst_rate_percent     NUMERIC(5,2),
    ADD COLUMN cgst_amount          NUMERIC(12,2),
    ADD COLUMN sgst_amount          NUMERIC(12,2),
    ADD COLUMN igst_amount          NUMERIC(12,2),
    ADD CONSTRAINT chk_asd_gst_fields_together CHECK (
        (gst_supplier_name IS NULL AND gst_supplier_gstin IS NULL AND gst_invoice_date IS NULL
            AND gst_taxable_value IS NULL AND gst_rate_percent IS NULL
            AND cgst_amount IS NULL AND sgst_amount IS NULL AND igst_amount IS NULL)
        OR (gst_supplier_name IS NOT NULL AND gst_supplier_gstin IS NOT NULL AND gst_invoice_date IS NOT NULL
            AND gst_taxable_value IS NOT NULL AND gst_rate_percent IS NOT NULL
            AND cgst_amount IS NOT NULL AND sgst_amount IS NOT NULL AND igst_amount IS NOT NULL)),
    ADD CONSTRAINT chk_asd_gst_rate CHECK (gst_rate_percent IS NULL OR gst_rate_percent IN (0, 5, 12, 18, 28)),
    ADD CONSTRAINT chk_asd_gst_amounts_not_negative CHECK (
        (gst_taxable_value IS NULL OR gst_taxable_value > 0)
        AND (cgst_amount IS NULL OR cgst_amount >= 0)
        AND (sgst_amount IS NULL OR sgst_amount >= 0)
        AND (igst_amount IS NULL OR igst_amount >= 0)),
    ADD CONSTRAINT chk_asd_gst_split CHECK (igst_amount IS NULL OR igst_amount = 0 OR (cgst_amount = 0 AND sgst_amount = 0));

CREATE INDEX idx_asd_pump_gst_invoice_date ON ancillary_stock_deliveries (pump_id, gst_invoice_date)
    WHERE gst_invoice_date IS NOT NULL;

-- 4. Pump test litres: the measure a nozzle is tested with each day (a hint at close) and the litres per nozzle per
--    shift above which returning test fuel needs a note.
ALTER TABLE pump_locations
    ADD COLUMN test_volume_litres         NUMERIC(6,2) NOT NULL DEFAULT 5.00,
    ADD COLUMN max_test_litres_per_nozzle NUMERIC(6,2) NOT NULL DEFAULT 10.00,
    ADD CONSTRAINT chk_pump_test_volume_range CHECK (test_volume_litres >= 0 AND test_volume_litres <= 100),
    ADD CONSTRAINT chk_pump_max_test_litres_range CHECK (max_test_litres_per_nozzle >= 0 AND max_test_litres_per_nozzle <= 1000);

-- 5. Litres a nozzle dispensed for its test and poured back into the tank. units_sold is the meter litres less these.
ALTER TABLE shift_fuel_readings
    ADD COLUMN test_litres NUMERIC(10,2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_sfr_test_litres_not_negative CHECK (test_litres >= 0);

ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'TEST_LITRES_ABOVE_MAX';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'SUPPLIER_OPENING_BALANCE_SET';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'SUPPLIER_DEFAULT_SET';
