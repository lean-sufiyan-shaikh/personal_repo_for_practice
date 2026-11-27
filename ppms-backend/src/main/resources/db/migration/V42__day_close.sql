-- Day close: a manager closes a business day, the owner approves it, and writes dated on a closed day are refused
-- until an owner or admin re-opens it. Every close stores an immutable summary snapshot as a new version.
CREATE TABLE day_closes (
    id                    BIGSERIAL       PRIMARY KEY,
    pump_id               BIGINT          NOT NULL,
    business_date         DATE            NOT NULL,
    status                VARCHAR(20)     NOT NULL,
    current_version       INTEGER         NOT NULL,
    closed_by_user_id     BIGINT          NOT NULL,
    closed_at             TIMESTAMPTZ     NOT NULL,
    approved_by_user_id   BIGINT,
    approved_at           TIMESTAMPTZ,
    balance_sheet_id      BIGINT,
    created_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_day_close_pump          FOREIGN KEY (pump_id)             REFERENCES pump_locations (id),
    CONSTRAINT fk_day_close_closed_by     FOREIGN KEY (closed_by_user_id)   REFERENCES users (id),
    CONSTRAINT fk_day_close_approved_by   FOREIGN KEY (approved_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_day_close_balance_sheet FOREIGN KEY (balance_sheet_id)    REFERENCES balance_sheets (id) ON DELETE SET NULL,
    CONSTRAINT uq_day_close_pump_date     UNIQUE (pump_id, business_date),
    CONSTRAINT chk_day_close_status       CHECK (status IN ('CLOSED', 'REOPENED', 'APPROVED')),
    CONSTRAINT chk_day_close_version_positive CHECK (current_version > 0),
    CONSTRAINT chk_day_close_approved_fields CHECK ((status = 'APPROVED') = (approved_at IS NOT NULL AND approved_by_user_id IS NOT NULL))
);

CREATE INDEX idx_day_close_pump_status ON day_closes (pump_id, status, business_date DESC);

-- One row per close; a re-close after a re-open adds the next version and keeps the earlier ones.
CREATE TABLE day_close_versions (
    id                    BIGSERIAL       PRIMARY KEY,
    day_close_id          BIGINT          NOT NULL,
    version               INTEGER         NOT NULL,
    summary               JSONB           NOT NULL,
    closed_by_user_id     BIGINT          NOT NULL,
    closed_at             TIMESTAMPTZ     NOT NULL,

    CONSTRAINT fk_day_close_version_day_close FOREIGN KEY (day_close_id)      REFERENCES day_closes (id),
    CONSTRAINT fk_day_close_version_closed_by FOREIGN KEY (closed_by_user_id) REFERENCES users (id),
    CONSTRAINT uq_day_close_version           UNIQUE (day_close_id, version),
    CONSTRAINT chk_day_close_version_number   CHECK (version > 0)
);

-- Owner questions, manager replies and the reason given for every re-open, in the order they were written.
CREATE TABLE day_close_comments (
    id                    BIGSERIAL       PRIMARY KEY,
    day_close_id          BIGINT          NOT NULL,
    kind                  VARCHAR(20)     NOT NULL,
    message               VARCHAR(1000)   NOT NULL,
    author_user_id        BIGINT          NOT NULL,
    created_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_day_close_comment_day_close FOREIGN KEY (day_close_id)   REFERENCES day_closes (id),
    CONSTRAINT fk_day_close_comment_author    FOREIGN KEY (author_user_id) REFERENCES users (id),
    CONSTRAINT chk_day_close_comment_kind     CHECK (kind IN ('QUESTION', 'REPLY', 'REOPEN_REASON')),
    CONSTRAINT chk_day_close_comment_not_blank CHECK (btrim(message) <> '')
);

CREATE INDEX idx_day_close_comment_day_close ON day_close_comments (day_close_id, created_at);

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'DAY_CLOSED';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'DAY_REOPENED';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'DAY_CLOSE_COMMENT';

ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'DAY_CLOSED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'DAY_APPROVED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'DAY_REOPENED';
ALTER TYPE audit_action ADD VALUE IF NOT EXISTS 'DAY_CLOSE_COMMENTED';
