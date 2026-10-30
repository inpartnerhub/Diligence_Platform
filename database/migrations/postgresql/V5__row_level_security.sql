-- Row-level security for project-scoped data (PRD 8.1 "every project is a security boundary", Bab 3 information
-- barriers, ADR-0004). Context is set per transaction by RowLevelSecurityTransactionManager:
--   app.user_id = Keycloak subject of the caller ('' for internal processing)
--   app.role    = caller's global role (lower case) or 'system' for message listeners / schedulers
-- The runtime role diligence_app does not own these tables, so policies always apply to it.

CREATE FUNCTION prj.app_user() RETURNS uuid
    LANGUAGE sql STABLE AS
$$ SELECT nullif(current_setting('app.user_id', true), '')::uuid $$;

CREATE FUNCTION prj.app_role() RETURNS text
    LANGUAGE sql STABLE AS
$$ SELECT coalesce(current_setting('app.role', true), '') $$;

CREATE FUNCTION prj.is_system() RETURNS boolean
    LANGUAGE sql STABLE AS
$$ SELECT prj.app_role() = 'system' $$;

-- SECURITY DEFINER: reads project_member as the owner, avoiding policy recursion on project_member itself.
CREATE FUNCTION prj.has_project_role(p_project_id uuid, p_roles text[]) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, prj AS
$$
SELECT EXISTS (SELECT 1
               FROM prj.project_member m
               WHERE m.project_id = p_project_id
                 AND m.user_id = prj.app_user()
                 AND m.project_role = ANY (p_roles)
                 AND (m.access_expires_at IS NULL OR m.access_expires_at > now()))
$$;
REVOKE ALL ON FUNCTION prj.has_project_role(uuid, text[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION prj.has_project_role(uuid, text[]) TO diligence_app;

-- Role sets used below.
--   members    : every project role
--   consultants: PARTNER, PROJECT_MANAGER, ANALYST, OBSERVER (internal team incl. read-only auditor)
--   editors    : PARTNER, PROJECT_MANAGER, ANALYST
--   managers   : PARTNER, PROJECT_MANAGER

-- ---------------------------------------------------------------- prj
ALTER TABLE prj.project ENABLE ROW LEVEL SECURITY;
CREATE POLICY project_read ON prj.project FOR SELECT USING (
    prj.is_system() OR prj.has_project_role(id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','CLIENT','TARGET','OBSERVER']));
CREATE POLICY project_insert ON prj.project FOR INSERT WITH CHECK (
    prj.is_system() OR prj.app_role() IN ('partner', 'project_manager'));
CREATE POLICY project_modify ON prj.project FOR UPDATE USING (
    prj.is_system() OR prj.has_project_role(id, ARRAY ['PARTNER','PROJECT_MANAGER']));
CREATE POLICY project_delete ON prj.project FOR DELETE USING (prj.is_system());

ALTER TABLE prj.workstream ENABLE ROW LEVEL SECURITY;
CREATE POLICY workstream_read ON prj.workstream FOR SELECT USING (
    prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','CLIENT','TARGET','OBSERVER']));
CREATE POLICY workstream_write ON prj.workstream FOR ALL
    USING (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER']))
    WITH CHECK (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER']));

ALTER TABLE prj.project_member ENABLE ROW LEVEL SECURITY;
-- Users see their own memberships; the internal team sees the whole roster. External parties never see each other.
CREATE POLICY project_member_read ON prj.project_member FOR SELECT USING (
    prj.is_system() OR user_id = prj.app_user()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER']));
CREATE POLICY project_member_write ON prj.project_member FOR ALL
    USING (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER']))
    WITH CHECK (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER']));

-- ---------------------------------------------------------------- req: everyone in the project sees progress
ALTER TABLE req.request_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY request_item_read ON req.request_item FOR SELECT USING (
    prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','CLIENT','TARGET','OBSERVER']));
CREATE POLICY request_item_write ON req.request_item FOR ALL
    USING (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','TARGET']))
    WITH CHECK (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','TARGET']));

ALTER TABLE req.request_item_document ENABLE ROW LEVEL SECURITY;
CREATE POLICY request_item_document_access ON req.request_item_document FOR ALL
    USING (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','TARGET','OBSERVER']))
    WITH CHECK (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','TARGET']));

-- ---------------------------------------------------------------- dr: data room — not visible to the client side
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY ['dr.folder', 'dr.document', 'dr.document_version'] LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY data_room_read ON %s FOR SELECT USING (
            prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','TARGET','OBSERVER']))$p$, t);
        EXECUTE format($p$CREATE POLICY data_room_write ON %s FOR ALL
            USING (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','TARGET']))
            WITH CHECK (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','TARGET']))$p$, t);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------- fnd: INFORMATION BARRIER
-- The target side never sees findings or risk ratings; the client sees only released findings (PRD Bab 3, FND-04).
ALTER TABLE fnd.finding ENABLE ROW LEVEL SECURITY;
CREATE POLICY finding_read ON fnd.finding FOR SELECT USING (
    prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER'])
        OR (status = 'RELEASED' AND prj.has_project_role(project_id, ARRAY ['CLIENT'])));
CREATE POLICY finding_write ON fnd.finding FOR ALL
    USING (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']))
    WITH CHECK (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']));

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY ['fnd.finding_document', 'fnd.finding_question'] LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY finding_link_read ON %s FOR SELECT USING (
            prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER']))$p$, t);
        EXECUTE format($p$CREATE POLICY finding_link_write ON %s FOR ALL
            USING (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']))
            WITH CHECK (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']))$p$, t);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------- qna
-- Target sees only approved (sent) questions (QNA-02); the client sees only its own questions, never the
-- internal team's correspondence (PRD Bab 3).
ALTER TABLE qna.question ENABLE ROW LEVEL SECURITY;
CREATE POLICY question_read ON qna.question FOR SELECT USING (
    prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER'])
        OR (status IN ('SENT', 'ANSWERED', 'CLOSED') AND prj.has_project_role(project_id, ARRAY ['TARGET']))
        OR (asked_by = prj.app_user() AND prj.has_project_role(project_id, ARRAY ['CLIENT'])));
CREATE POLICY question_write_team ON qna.question FOR ALL
    USING (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']))
    WITH CHECK (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']));
CREATE POLICY question_answer_target ON qna.question FOR UPDATE
    USING (status IN ('SENT', 'ANSWERED') AND prj.has_project_role(project_id, ARRAY ['TARGET']))
    WITH CHECK (status IN ('SENT', 'ANSWERED') AND prj.has_project_role(project_id, ARRAY ['TARGET']));
CREATE POLICY question_ask_client ON qna.question FOR INSERT WITH CHECK (
    asked_by = prj.app_user() AND status IN ('DRAFT', 'PENDING_APPROVAL')
        AND prj.has_project_role(project_id, ARRAY ['CLIENT']));

-- ---------------------------------------------------------------- rpt: clients see issued reports only
ALTER TABLE rpt.report ENABLE ROW LEVEL SECURITY;
CREATE POLICY report_read ON rpt.report FOR SELECT USING (
    prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER'])
        OR (approval_status = 'ISSUED' AND prj.has_project_role(project_id, ARRAY ['CLIENT'])));
CREATE POLICY report_write ON rpt.report FOR ALL
    USING (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']))
    WITH CHECK (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']));

ALTER TABLE rpt.report_finding ENABLE ROW LEVEL SECURITY;
CREATE POLICY report_finding_access ON rpt.report_finding FOR ALL
    USING (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER']))
    WITH CHECK (prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']));
