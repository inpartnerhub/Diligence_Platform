-- #154: row-level security on the identity tables. Until now any transaction running as diligence_app (a client or
-- target user's included) could read every organization's users and e-mail addresses; only the controllers'
-- @PreAuthorize kept them away. The policies below follow how the application actually reads these tables:
--   * the internal team (super_admin, partner, project_manager, analyst) works with the whole directory;
--   * an external user (client, target, observer) sees their own account and organization, the consultant team, the
--     people they currently share a project with, and the client/target organizations of their projects;
--   * writes are for the Super Admin and internal processing, plus a user's own account (login bookkeeping,
--     just-in-time SSO provisioning, profile) and own invitation (accepted on first sign-in).

CREATE FUNCTION adm.is_internal() RETURNS boolean
    LANGUAGE sql STABLE AS
$$ SELECT prj.app_role() IN ('super_admin', 'partner', 'project_manager', 'analyst') $$;
GRANT EXECUTE ON FUNCTION adm.is_internal() TO diligence_app;

-- SECURITY DEFINER: reads the caller's own account without going through the policy being defined.
CREATE FUNCTION adm.own_organization() RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, adm, pg_temp AS
$$ SELECT u.organization_id FROM adm.user_account u WHERE u.id = prj.app_user() $$;
REVOKE ALL ON FUNCTION adm.own_organization() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION adm.own_organization() TO diligence_app;

-- SECURITY DEFINER: the caller cannot read other people's memberships (prj.project_member policy).
CREATE FUNCTION prj.shares_project_with(p_user_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, prj, pg_temp AS
$$
SELECT EXISTS (SELECT 1
               FROM prj.project_member mine
                        JOIN prj.project_member theirs ON theirs.project_id = mine.project_id
               WHERE mine.user_id = prj.app_user()
                 AND theirs.user_id = p_user_id
                 AND (mine.access_expires_at IS NULL OR mine.access_expires_at > now())
                 AND (theirs.access_expires_at IS NULL OR theirs.access_expires_at > now()))
$$;
REVOKE ALL ON FUNCTION prj.shares_project_with(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION prj.shares_project_with(uuid) TO diligence_app;

-- SECURITY DEFINER: whether the organization is the client or target of a project the caller is a member of.
CREATE FUNCTION prj.is_member_organization(p_organization_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, prj, pg_temp AS
$$
SELECT EXISTS (SELECT 1
               FROM prj.project p
                        JOIN prj.project_member m ON m.project_id = p.id
               WHERE m.user_id = prj.app_user()
                 AND (m.access_expires_at IS NULL OR m.access_expires_at > now())
                 AND p_organization_id IN (p.client_organization_id, p.target_organization_id))
$$;
REVOKE ALL ON FUNCTION prj.is_member_organization(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION prj.is_member_organization(uuid) TO diligence_app;

-- ---------------------------------------------------------------- user accounts
ALTER TABLE adm.user_account ENABLE ROW LEVEL SECURITY;
CREATE POLICY user_account_read ON adm.user_account FOR SELECT USING (
    prj.is_system() OR adm.is_internal() OR id = prj.app_user()
        OR global_role IN ('PARTNER', 'PROJECT_MANAGER', 'ANALYST')
        OR prj.shares_project_with(id));
CREATE POLICY user_account_insert ON adm.user_account FOR INSERT WITH CHECK (
    prj.is_system() OR prj.app_role() = 'super_admin' OR id = prj.app_user());
CREATE POLICY user_account_update ON adm.user_account FOR UPDATE
    USING (prj.is_system() OR prj.app_role() = 'super_admin' OR id = prj.app_user())
    WITH CHECK (prj.is_system() OR prj.app_role() = 'super_admin' OR id = prj.app_user());
CREATE POLICY user_account_delete ON adm.user_account FOR DELETE USING (
    prj.is_system() OR prj.app_role() = 'super_admin');

-- A user's own row is writable only for bookkeeping: last login, invited → active, language (and the JPA version).
-- Role, organization, e-mail, name and MFA flag change only through the Super Admin or internal processing.
CREATE FUNCTION adm.protect_own_account() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF prj.is_system() OR prj.app_role() = 'super_admin' THEN
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.email IS DISTINCT FROM OLD.email
        OR NEW.display_name IS DISTINCT FROM OLD.display_name
        OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
        OR NEW.global_role IS DISTINCT FROM OLD.global_role
        OR NEW.mfa_enabled IS DISTINCT FROM OLD.mfa_enabled
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR (NEW.status IS DISTINCT FROM OLD.status AND NOT (OLD.status = 'INVITED' AND NEW.status = 'ACTIVE')) THEN
        RAISE EXCEPTION 'only the Super Admin changes role, organization, e-mail, name or status of an account'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER user_account_protect_own BEFORE UPDATE ON adm.user_account
    FOR EACH ROW EXECUTE FUNCTION adm.protect_own_account();

-- ---------------------------------------------------------------- organizations
ALTER TABLE adm.organization ENABLE ROW LEVEL SECURITY;
CREATE POLICY organization_read ON adm.organization FOR SELECT USING (
    prj.is_system() OR adm.is_internal() OR id = adm.own_organization() OR prj.is_member_organization(id));
CREATE POLICY organization_write ON adm.organization FOR ALL
    USING (prj.is_system() OR prj.app_role() = 'super_admin')
    WITH CHECK (prj.is_system() OR prj.app_role() = 'super_admin');

-- ---------------------------------------------------------------- invitations
ALTER TABLE adm.invitation ENABLE ROW LEVEL SECURITY;
CREATE POLICY invitation_read ON adm.invitation FOR SELECT USING (
    prj.is_system() OR prj.app_role() = 'super_admin' OR user_id = prj.app_user());
CREATE POLICY invitation_accept ON adm.invitation FOR UPDATE
    USING (prj.is_system() OR prj.app_role() = 'super_admin' OR user_id = prj.app_user())
    WITH CHECK (prj.is_system() OR prj.app_role() = 'super_admin' OR user_id = prj.app_user());
CREATE POLICY invitation_manage ON adm.invitation FOR INSERT WITH CHECK (
    prj.is_system() OR prj.app_role() = 'super_admin');
CREATE POLICY invitation_delete ON adm.invitation FOR DELETE USING (
    prj.is_system() OR prj.app_role() = 'super_admin');

-- ---------------------------------------------------------------- groups (folder grants are resolved by the
-- SECURITY DEFINER dr.has_folder_access, so external users do not need to read them)
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY ['adm.user_group', 'adm.group_member'] LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY group_read ON %s FOR SELECT USING (prj.is_system() OR adm.is_internal())$p$, t);
        EXECUTE format($p$CREATE POLICY group_write ON %s FOR ALL
            USING (prj.is_system() OR prj.app_role() = 'super_admin')
            WITH CHECK (prj.is_system() OR prj.app_role() = 'super_admin')$p$, t);
    END LOOP;
END
$$;
