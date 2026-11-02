-- Sprint 4 / Phase 2: Retention policy and automated project data deletion (PRD ADM-06, UU No. 27/2022 - PDP).

-- Default retention policy for organizations (in days, e.g. 30, 90, 365).
ALTER TABLE adm.organization
    ADD COLUMN default_retention_days INT NOT NULL DEFAULT 365
    CONSTRAINT organization_default_retention_days_check CHECK (default_retention_days > 0);

-- Configurable retention schedule and purge timestamp per project.
ALTER TABLE prj.project
    ADD COLUMN retention_period_days INT NOT NULL DEFAULT 365
    CONSTRAINT project_retention_period_days_check CHECK (retention_period_days > 0),
    ADD COLUMN purged_at TIMESTAMPTZ;

-- Allow PURGED status on projects.
ALTER TABLE prj.project DROP CONSTRAINT IF EXISTS project_status_check;
ALTER TABLE prj.project ADD CONSTRAINT project_status_check
    CHECK (status IN ('ACTIVE', 'ARCHIVED', 'PURGED'));

-- Index for scheduled retention expiry scans.
CREATE INDEX project_retention_idx ON prj.project (status, archived_at, purged_at);

