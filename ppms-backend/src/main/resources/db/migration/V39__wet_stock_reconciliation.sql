-- Share of litres sold that a tank may lose over 7 days before the owner is alerted. NULL means the 0.5% default.
ALTER TABLE underground_tanks
    ADD COLUMN loss_alert_percent NUMERIC(5,2) DEFAULT 0.50,
    ADD CONSTRAINT chk_tank_loss_alert_percent_range
        CHECK (loss_alert_percent IS NULL OR (loss_alert_percent > 0 AND loss_alert_percent <= 100));

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'WET_STOCK_LOSS';
