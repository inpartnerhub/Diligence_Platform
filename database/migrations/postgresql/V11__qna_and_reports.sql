-- Sprint 13–14, Q&A with approval (PRD QNA-01…QNA-03) and the report builder (RPT-01).
--   * questions get a number, a workstream and an optional finding link; every status change is kept
--   * answers move to their own table so row-level security can hold them back from the client until the team has
--     reviewed and closed the question (the target sees only questions sent to it, never internal drafts)
--   * reports record the generated Word/PDF files, who issued them and when; issued reports are immutable

-- ---------------------------------------------------------------- qna: schema
ALTER TABLE qna.question
    ADD COLUMN number        TEXT,
    ADD COLUMN workstream_id UUID,
    ADD COLUMN finding_id    UUID,
    ADD COLUMN updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN approved_by   UUID,
    ADD COLUMN sent_at       TIMESTAMPTZ,
    ADD COLUMN closed_at     TIMESTAMPTZ,
    -- Last message from the team to whoever acts next: why a question was returned, or what the target should add.
    ADD COLUMN feedback      TEXT;
UPDATE qna.question SET number = 'Q-' || upper(substr(id::text, 1, 8)) WHERE number IS NULL;
ALTER TABLE qna.question ALTER COLUMN number SET NOT NULL;
CREATE UNIQUE INDEX question_number_uq ON qna.question (project_id, number);
COMMENT ON COLUMN qna.question.answer IS 'Unused since V11: answers live in qna.answer (row-level security).';

CREATE TABLE qna.answer
(
    question_id UUID PRIMARY KEY REFERENCES qna.question (id) ON DELETE CASCADE,
    project_id  UUID        NOT NULL,
    body        TEXT        NOT NULL CHECK (length(body) BETWEEN 1 AND 10000),
    answered_by UUID        NOT NULL,
    answered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE qna.answer_document
(
    question_id UUID        NOT NULL REFERENCES qna.question (id) ON DELETE CASCADE,
    document_id UUID        NOT NULL,
    project_id  UUID        NOT NULL,
    linked_by   UUID        NOT NULL,
    linked_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (question_id, document_id)
);

CREATE TABLE qna.question_status_history
(
    id          UUID PRIMARY KEY,
    question_id UUID        NOT NULL REFERENCES qna.question (id) ON DELETE CASCADE,
    project_id  UUID        NOT NULL,
    from_status TEXT,
    to_status   TEXT        NOT NULL,
    changed_by  UUID,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    note        TEXT
);
CREATE INDEX question_status_history_question_idx ON qna.question_status_history (question_id, changed_at);

-- Numbers are allocated across all of a project's questions, including those the caller cannot see (a client only
-- sees its own); SECURITY DEFINER reads them as the owner. The unique index guards against concurrent allocation.
CREATE FUNCTION qna.next_question_number(p_project_id uuid) RETURNS text
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, qna AS
$$
SELECT 'Q-' || lpad((coalesce(max(substring(number FROM 3)::int) FILTER (WHERE number ~ '^Q-[0-9]{1,8}$'), 0)
    + 1)::text, 3, '0')
FROM qna.question
WHERE project_id = p_project_id
$$;
REVOKE ALL ON FUNCTION qna.next_question_number(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION qna.next_question_number(uuid) TO diligence_app;

-- ---------------------------------------------------------------- qna: row-level security
-- The question itself follows V5 (team: all; target: SENT/ANSWERED/CLOSED; client: its own). Answers are stricter.
ALTER TABLE qna.answer ENABLE ROW LEVEL SECURITY;
CREATE POLICY answer_read ON qna.answer FOR SELECT USING (
    prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER'])
        OR (prj.has_project_role(project_id, ARRAY ['TARGET'])
            AND EXISTS (SELECT 1 FROM qna.question q WHERE q.id = question_id))
        OR (prj.has_project_role(project_id, ARRAY ['CLIENT'])
            AND EXISTS (SELECT 1 FROM qna.question q
                        WHERE q.id = question_id AND q.status = 'CLOSED' AND q.asked_by = prj.app_user())));
-- The target answers questions that are open for it (SENT, or ANSWERED and not yet closed).
CREATE POLICY answer_write ON qna.answer FOR ALL
    USING (prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])
        OR (prj.has_project_role(project_id, ARRAY ['TARGET'])
            AND EXISTS (SELECT 1 FROM qna.question q
                        WHERE q.id = question_id AND q.status IN ('SENT', 'ANSWERED'))))
    WITH CHECK (prj.is_system()
        OR (answered_by = prj.app_user()
            AND (prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])
                OR (prj.has_project_role(project_id, ARRAY ['TARGET'])
                    AND EXISTS (SELECT 1 FROM qna.question q
                                WHERE q.id = question_id AND q.status IN ('SENT', 'ANSWERED'))))));

ALTER TABLE qna.answer_document ENABLE ROW LEVEL SECURITY;
CREATE POLICY answer_document_read ON qna.answer_document FOR SELECT USING (
    prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER'])
        OR (prj.has_project_role(project_id, ARRAY ['TARGET'])
            AND EXISTS (SELECT 1 FROM qna.question q WHERE q.id = question_id)));
CREATE POLICY answer_document_write ON qna.answer_document FOR ALL
    USING (prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])
        OR (prj.has_project_role(project_id, ARRAY ['TARGET'])
            AND EXISTS (SELECT 1 FROM qna.question q
                        WHERE q.id = question_id AND q.status IN ('SENT', 'ANSWERED'))))
    WITH CHECK (prj.is_system()
        OR (linked_by = prj.app_user()
            AND (prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])
                OR (prj.has_project_role(project_id, ARRAY ['TARGET'])
                    AND EXISTS (SELECT 1 FROM qna.question q
                                WHERE q.id = question_id AND q.status IN ('SENT', 'ANSWERED'))))));

-- The history is the team's working paper; everyone who moves a question records their own step.
ALTER TABLE qna.question_status_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY question_status_history_read ON qna.question_status_history FOR SELECT USING (
    prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER']));
CREATE POLICY question_status_history_insert ON qna.question_status_history FOR INSERT WITH CHECK (
    prj.is_system()
        OR (changed_by = prj.app_user()
            AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','TARGET','CLIENT'])));

-- ---------------------------------------------------------------- rpt: schema
ALTER TABLE rpt.report
    ADD COLUMN pdf_object_key TEXT,
    ADD COLUMN language       TEXT        NOT NULL DEFAULT 'id' CHECK (language IN ('id', 'en')),
    ADD COLUMN finding_count  INT         NOT NULL DEFAULT 0,
    ADD COLUMN size_bytes     BIGINT,
    ADD COLUMN issued_by      UUID,
    ADD COLUMN issued_at      TIMESTAMPTZ;
CREATE UNIQUE INDEX report_version_uq ON rpt.report (project_id, version_number);

-- ---------------------------------------------------------------- rpt: row-level security
-- Drafts are generated by the team; only partners issue, and an issued report is never changed or deleted.
DROP POLICY report_write ON rpt.report;
CREATE POLICY report_insert ON rpt.report FOR INSERT WITH CHECK (
    prj.is_system()
        OR (approval_status = 'DRAFT'
            AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])));
CREATE POLICY report_update ON rpt.report FOR UPDATE
    USING (prj.is_system()
        OR (approval_status <> 'ISSUED'
            AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])))
    WITH CHECK (prj.is_system()
        OR (approval_status <> 'ISSUED'
            AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']))
        OR (approval_status = 'ISSUED' AND prj.has_project_role(project_id, ARRAY ['PARTNER'])));
CREATE POLICY report_delete ON rpt.report FOR DELETE USING (
    prj.is_system()
        OR (approval_status = 'DRAFT'
            AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])));
