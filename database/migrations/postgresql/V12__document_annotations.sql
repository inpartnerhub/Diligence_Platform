-- Sprint Phase 2, in-viewer annotations linked to findings (PRD DOC-08, FND-02).
--   * rectangular coordinates on document pages (x, y, width, height as percentages)
--   * internal comments / notes attached to regions
--   * optional link to a finding (fnd.finding)
--   * row-level security: only internal project team members (PARTNER, PROJECT_MANAGER, ANALYST, OBSERVER) can read;
--     only PARTNER, PROJECT_MANAGER, ANALYST can create, edit or delete; target users cannot see annotations.

CREATE TABLE dr.document_annotation
(
    id              UUID PRIMARY KEY,
    project_id      UUID             NOT NULL,
    document_id     UUID             NOT NULL REFERENCES dr.document (id) ON DELETE CASCADE,
    page_number     INT              NOT NULL CHECK (page_number >= 1),
    x_pct           DOUBLE PRECISION NOT NULL CHECK (x_pct >= 0 AND x_pct <= 100),
    y_pct           DOUBLE PRECISION NOT NULL CHECK (y_pct >= 0 AND y_pct <= 100),
    width_pct       DOUBLE PRECISION NOT NULL CHECK (width_pct > 0 AND width_pct <= 100),
    height_pct      DOUBLE PRECISION NOT NULL CHECK (height_pct > 0 AND height_pct <= 100),
    color           TEXT,
    comment         TEXT,
    finding_id      UUID,
    created_by      UUID             NOT NULL,
    created_by_name TEXT,
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX document_annotation_doc_page_idx ON dr.document_annotation (document_id, page_number);
CREATE INDEX document_annotation_finding_idx ON dr.document_annotation (finding_id);

ALTER TABLE dr.document_annotation ENABLE ROW LEVEL SECURITY;

CREATE POLICY document_annotation_read ON dr.document_annotation FOR SELECT USING (
    prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER']));

CREATE POLICY document_annotation_write ON dr.document_annotation FOR ALL
    USING (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']))
    WITH CHECK (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']));
