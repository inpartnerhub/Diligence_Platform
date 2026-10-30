-- Sprint 3–4, project management (PRJ-01…PRJ-04).
--   * project bootstrap: the creator of a project may manage it before any membership exists, so the first
--     PROJECT_MANAGER membership and the workstreams can be inserted in the same transaction (ADR-0004)
--   * template reference and archive timestamp on projects

ALTER TABLE prj.project
    ADD COLUMN template_id UUID REFERENCES prj.project_template (id),
    ADD COLUMN archived_at TIMESTAMPTZ;

-- Templates are global configuration (PRD PRJ-03); keep the name unique.
ALTER TABLE prj.project_template
    ADD COLUMN description TEXT;
CREATE UNIQUE INDEX project_template_name_uq ON prj.project_template (lower(name));

-- SECURITY DEFINER: reads prj.project as the owner, so the check works before the caller is a member.
CREATE FUNCTION prj.is_project_creator(p_project_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, prj AS
$$
SELECT EXISTS (SELECT 1 FROM prj.project p WHERE p.id = p_project_id AND p.created_by = prj.app_user())
$$;
REVOKE ALL ON FUNCTION prj.is_project_creator(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION prj.is_project_creator(uuid) TO diligence_app;

-- Managers of a project: its partner(s) and project manager(s), plus the creator during bootstrap.
CREATE FUNCTION prj.can_manage(p_project_id uuid) RETURNS boolean
    LANGUAGE sql STABLE AS
$$
SELECT prj.is_system()
           OR prj.has_project_role(p_project_id, ARRAY ['PARTNER','PROJECT_MANAGER'])
           OR prj.is_project_creator(p_project_id)
$$;
GRANT EXECUTE ON FUNCTION prj.can_manage(uuid) TO diligence_app;

DROP POLICY project_read ON prj.project;
CREATE POLICY project_read ON prj.project FOR SELECT USING (
    prj.is_system() OR created_by = prj.app_user()
        OR prj.has_project_role(id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','CLIENT','TARGET','OBSERVER']));

DROP POLICY project_modify ON prj.project;
CREATE POLICY project_modify ON prj.project FOR UPDATE USING (prj.can_manage(id)) WITH CHECK (prj.can_manage(id));

DROP POLICY workstream_write ON prj.workstream;
CREATE POLICY workstream_write ON prj.workstream FOR ALL
    USING (prj.can_manage(project_id)) WITH CHECK (prj.can_manage(project_id));

DROP POLICY project_member_write ON prj.project_member;
CREATE POLICY project_member_write ON prj.project_member FOR ALL
    USING (prj.can_manage(project_id)) WITH CHECK (prj.can_manage(project_id));
