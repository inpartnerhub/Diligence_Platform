-- Sprint Phase 3, AI-driven key clause extraction and contract search (PRD DOC-09).
-- Stores detected key contractual clauses (Change of Control, Non-Compete, Assignment, Indemnity, Termination, etc.)
-- linked to document versions, page numbers, and confidence scores.

CREATE TABLE dr.document_clause
(
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id     UUID             NOT NULL REFERENCES prj.project (id) ON DELETE CASCADE,
    document_id    UUID             NOT NULL REFERENCES dr.document (id) ON DELETE CASCADE,
    version_id     UUID             NOT NULL REFERENCES dr.document_version (id) ON DELETE CASCADE,
    clause_type    VARCHAR(64)      NOT NULL,
    confidence     DOUBLE PRECISION NOT NULL DEFAULT 0.90 CHECK (confidence >= 0.0 AND confidence <= 1.0),
    page_number    INT              NOT NULL CHECK (page_number >= 1),
    extracted_text TEXT             NOT NULL,
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX document_clause_doc_page_idx ON dr.document_clause (document_id, page_number);
CREATE INDEX document_clause_project_type_idx ON dr.document_clause (project_id, clause_type);
CREATE INDEX document_clause_version_idx ON dr.document_clause (version_id);

ALTER TABLE dr.document_clause ENABLE ROW LEVEL SECURITY;

CREATE POLICY document_clause_read ON dr.document_clause FOR SELECT USING (
    prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER', 'PROJECT_MANAGER', 'ANALYST', 'OBSERVER']));

CREATE POLICY document_clause_write ON dr.document_clause FOR ALL
    USING (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER', 'PROJECT_MANAGER', 'ANALYST']))
    WITH CHECK (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER', 'PROJECT_MANAGER', 'ANALYST']));
