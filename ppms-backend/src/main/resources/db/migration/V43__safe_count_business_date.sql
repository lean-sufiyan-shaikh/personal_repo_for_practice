-- The business day a safe count is for. A manager may count for yesterday until the pump's cut-off time the next
-- morning (late); after that only an owner or admin may back-date a count, with a reason.
ALTER TABLE safe_counts
    ADD COLUMN count_for_date   DATE,
    ADD COLUMN late             BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN back_date_reason VARCHAR(500);

UPDATE safe_counts SET count_for_date = (counted_at AT TIME ZONE 'Asia/Kolkata')::date;

ALTER TABLE safe_counts
    ALTER COLUMN count_for_date SET NOT NULL,
    ADD CONSTRAINT chk_safe_count_back_date_reason
        CHECK (back_date_reason IS NULL OR (late AND btrim(back_date_reason) <> ''));

CREATE INDEX idx_safe_count_pump_date ON safe_counts (pump_id, count_for_date, counted_at DESC);

ALTER TABLE pump_locations
    ADD COLUMN safe_count_cutoff_time TIME NOT NULL DEFAULT '10:00',
    ADD CONSTRAINT chk_pump_safe_count_cutoff_whole_minute CHECK (EXTRACT(SECOND FROM safe_count_cutoff_time) = 0);

ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'SAFE_COUNTED_LATE';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'SAFE_COUNT_BACK_DATED';
