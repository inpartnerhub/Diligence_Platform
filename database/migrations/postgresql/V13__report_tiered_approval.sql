-- Sprint Phase 2, tiered report approval workflow with version history (PRD RPT-03).
--   * multi-stage approval: DRAFT -> SUBMITTED_FOR_REVIEW -> APPROVED_BY_MANAGER -> RELEASED_BY_PARTNER
--   * major / minor version increments (v1.0, v1.1, v2.0)
--   * four-eyes principle tracking (submitted_by, manager_approved_by, issued_by)
--   * report review history / audit trail with review notes

ALTER TABLE rpt.report
    ADD COLUMN major_version       INT         NOT NULL DEFAULT 1,
    ADD COLUMN minor_version       INT         NOT NULL DEFAULT 0,
    ADD COLUMN version_label       TEXT        NOT NULL DEFAULT 'v1.0',
    ADD COLUMN submitted_by        UUID,
    ADD COLUMN submitted_at        TIMESTAMPTZ,
    ADD COLUMN manager_approved_by UUID,
    ADD COLUMN manager_approved_at TIMESTAMPTZ;

-- Backfill version_label for existing reports based on version_number
UPDATE rpt.report SET version_label = 'v' || version_number || '.0';

-- Update approval status constraint to support tiered approval lifecycle
ALTER TABLE rpt.report DROP CONSTRAINT IF EXISTS report_approval_status_check;
ALTER TABLE rpt.report ADD CONSTRAINT report_approval_status_check
    CHECK (approval_status IN ('DRAFT', 'SUBMITTED_FOR_REVIEW', 'APPROVED_BY_MANAGER', 'RELEASED_BY_PARTNER', 'IN_REVIEW', 'APPROVED', 'ISSUED'));

-- Migrate existing status values
UPDATE rpt.report SET approval_status = 'RELEASED_BY_PARTNER' WHERE approval_status = 'ISSUED';
UPDATE rpt.report SET approval_status = 'APPROVED_BY_MANAGER' WHERE approval_status = 'APPROVED';
UPDATE rpt.report SET approval_status = 'SUBMITTED_FOR_REVIEW' WHERE approval_status = 'IN_REVIEW';

-- Status history table for report audit trail (RPT-03)
CREATE TABLE rpt.report_status_history
(
    id          UUID PRIMARY KEY,
    report_id   UUID        NOT NULL REFERENCES rpt.report (id) ON DELETE CASCADE,
    project_id  UUID        NOT NULL,
    from_status TEXT,
    to_status   TEXT        NOT NULL,
    changed_by  UUID        NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    note        TEXT
);
CREATE INDEX report_status_history_report_idx ON rpt.report_status_history (report_id, changed_at);

ALTER TABLE rpt.report_status_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY report_status_history_read ON rpt.report_status_history FOR SELECT USING (
    prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER']));
CREATE POLICY report_status_history_insert ON rpt.report_status_history FOR INSERT WITH CHECK (
    prj.is_system()
        OR (changed_by = prj.app_user()
            AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])));

-- Update RLS policies on rpt.report
DROP POLICY IF EXISTS report_update ON rpt.report;
CREATE POLICY report_update ON rpt.report FOR UPDATE
    USING (prj.is_system()
        OR (approval_status NOT IN ('RELEASED_BY_PARTNER', 'ISSUED')
            AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']))
        OR (approval_status IN ('RELEASED_BY_PARTNER', 'ISSUED') AND prj.has_project_role(project_id, ARRAY ['PARTNER'])))
    WITH CHECK (prj.is_system()
        OR (approval_status NOT IN ('RELEASED_BY_PARTNER', 'ISSUED')
            AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']))
        OR (approval_status IN ('RELEASED_BY_PARTNER', 'ISSUED') AND prj.has_project_role(project_id, ARRAY ['PARTNER'])));

DROP POLICY IF EXISTS report_delete ON rpt.report;
CREATE POLICY report_delete ON rpt.report FOR DELETE USING (
    prj.is_system()
        OR (approval_status = 'DRAFT'
            AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])));
