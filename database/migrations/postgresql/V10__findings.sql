-- Sprint 11–12, findings and risk register (PRD FND-01…FND-04).
--   * finding numbers per workstream, workflow timestamps and the people who submitted, approved and released
--   * status history and internal review notes (never visible outside the internal team)
--   * who linked which source document (KPI "≥ 95% of findings linked to source documents")
--   * row-level security: released and archived findings are immutable, only partners release (PRD Bab 3)
--   * risk levels of unreleased findings are recomputed when the risk matrix thresholds change (FND-03)

-- ---------------------------------------------------------------- schema
ALTER TABLE fnd.finding
    ADD COLUMN number         TEXT,
    ADD COLUMN updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN submitted_by   UUID,
    ADD COLUMN submitted_at   TIMESTAMPTZ,
    ADD COLUMN approved_by    UUID,
    ADD COLUMN approved_at    TIMESTAMPTZ,
    ADD COLUMN released_by    UUID,
    ADD COLUMN released_at    TIMESTAMPTZ,
    ADD COLUMN archive_reason TEXT;
UPDATE fnd.finding SET number = 'F-' || upper(substr(id::text, 1, 8)) WHERE number IS NULL;
ALTER TABLE fnd.finding ALTER COLUMN number SET NOT NULL;
CREATE UNIQUE INDEX finding_number_uq ON fnd.finding (project_id, number);
ALTER TABLE fnd.finding ALTER COLUMN currency TYPE TEXT USING trim(currency);
ALTER TABLE fnd.finding
    ADD CONSTRAINT finding_currency_code CHECK (currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT finding_rating_complete CHECK ((impact IS NULL) = (likelihood IS NULL)),
    ADD CONSTRAINT finding_monetary_currency CHECK (monetary_impact IS NULL OR currency IS NOT NULL);

ALTER TABLE fnd.finding_document
    ADD COLUMN note      TEXT,
    ADD COLUMN linked_by UUID,
    ADD COLUMN linked_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE fnd.finding_status_history
(
    id          UUID PRIMARY KEY,
    finding_id  UUID        NOT NULL REFERENCES fnd.finding (id) ON DELETE CASCADE,
    project_id  UUID        NOT NULL,
    from_status TEXT,
    to_status   TEXT        NOT NULL,
    changed_by  UUID,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    note        TEXT
);
CREATE INDEX finding_status_history_finding_idx ON fnd.finding_status_history (finding_id, changed_at);

CREATE TABLE fnd.finding_comment
(
    id         UUID PRIMARY KEY,
    finding_id UUID        NOT NULL REFERENCES fnd.finding (id) ON DELETE CASCADE,
    project_id UUID        NOT NULL,
    author_id  UUID        NOT NULL,
    body       TEXT        NOT NULL CHECK (length(body) BETWEEN 1 AND 4000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX finding_comment_finding_idx ON fnd.finding_comment (finding_id, created_at);

-- ---------------------------------------------------------------- row-level security
DROP POLICY finding_write ON fnd.finding;

CREATE POLICY finding_insert ON fnd.finding FOR INSERT WITH CHECK (
    prj.is_system()
        OR (status = 'DRAFT' AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])));
-- Open findings are maintained by the team; a released finding can only be archived, by a partner. Only partners
-- release findings to the client.
CREATE POLICY finding_update ON fnd.finding FOR UPDATE
    USING (prj.is_system()
        OR (status NOT IN ('RELEASED', 'ARCHIVED')
            AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']))
        OR (status = 'RELEASED' AND prj.has_project_role(project_id, ARRAY ['PARTNER'])))
    WITH CHECK (prj.is_system()
        OR (status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'ARCHIVED')
            AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']))
        OR (status = 'RELEASED' AND prj.has_project_role(project_id, ARRAY ['PARTNER'])));
CREATE POLICY finding_delete ON fnd.finding FOR DELETE USING (
    prj.is_system()
        OR (status = 'DRAFT' AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])));

-- History and review notes are internal working papers: the client never reads them, even for released findings.
ALTER TABLE fnd.finding_status_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY finding_status_history_read ON fnd.finding_status_history FOR SELECT USING (
    prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER']));
CREATE POLICY finding_status_history_insert ON fnd.finding_status_history FOR INSERT WITH CHECK (
    prj.is_system()
        OR (changed_by = prj.app_user()
            AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])));

ALTER TABLE fnd.finding_comment ENABLE ROW LEVEL SECURITY;
CREATE POLICY finding_comment_read ON fnd.finding_comment FOR SELECT USING (
    prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER']));
CREATE POLICY finding_comment_insert ON fnd.finding_comment FOR INSERT WITH CHECK (
    prj.is_system()
        OR (author_id = prj.app_user()
            AND prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])));

-- ---------------------------------------------------------------- risk matrix thresholds (FND-03)
-- SECURITY DEFINER: administrators hold no project roles, yet a threshold change must re-rate the open findings of
-- every project. Released and archived findings keep the level they were released with.
CREATE FUNCTION fnd.recompute_risk_levels() RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, fnd AS
$$
DECLARE
    changed integer;
BEGIN
    UPDATE fnd.finding f
    SET risk_score = f.impact * f.likelihood,
        risk_level = CASE
                         WHEN f.impact * f.likelihood >= c.critical_from THEN 'CRITICAL'
                         WHEN f.impact * f.likelihood >= c.high_from THEN 'HIGH'
                         WHEN f.impact * f.likelihood >= c.medium_from THEN 'MEDIUM'
                         ELSE 'LOW' END
    FROM fnd.risk_matrix_config c
    WHERE c.id = 1
      AND f.impact IS NOT NULL
      AND f.status NOT IN ('RELEASED', 'ARCHIVED');
    GET DIAGNOSTICS changed = ROW_COUNT;
    RETURN changed;
END
$$;
REVOKE ALL ON FUNCTION fnd.recompute_risk_levels() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fnd.recompute_risk_levels() TO diligence_app;
