-- #173 (found while testing): adm.protect_own_account() (#154) applied to every session without an application
-- context, including the schema owner (migrations, maintenance SQL, test seeding), which then could not change an
-- account's status. Like row-level security, it is meant for the runtime role only: the application's transactions
-- run as diligence_app (SET LOCAL ROLE), so any other role is administrative access and is not restricted here.
CREATE OR REPLACE FUNCTION adm.protect_own_account() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF current_user <> 'diligence_app' OR prj.is_system() OR prj.app_role() = 'super_admin' THEN
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
