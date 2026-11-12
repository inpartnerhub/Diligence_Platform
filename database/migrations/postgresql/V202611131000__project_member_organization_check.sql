-- #155: CLIENT and TARGET members must belong to the project's client and target organization. MemberService already
-- checks this when a member is added; this trigger makes the database refuse it too (another code path, a bug or a
-- manual statement). prj.project.*_organization_id and adm.user_account live in different module schemas without a
-- foreign key, hence a trigger. SECURITY DEFINER: the caller may not see the account or the project row itself.
CREATE FUNCTION prj.check_member_organization() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, prj, adm, pg_temp AS
$$
DECLARE
    expected uuid;
    actual   uuid;
BEGIN
    IF NEW.project_role NOT IN ('CLIENT', 'TARGET') THEN
        RETURN NEW;
    END IF;
    SELECT CASE NEW.project_role WHEN 'CLIENT' THEN p.client_organization_id ELSE p.target_organization_id END
    INTO expected
    FROM prj.project p
    WHERE p.id = NEW.project_id;
    SELECT u.organization_id INTO actual FROM adm.user_account u WHERE u.id = NEW.user_id;
    IF actual IS NULL OR actual IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'a % member must belong to the project''s % organization', NEW.project_role,
            lower(NEW.project_role::text)
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION prj.check_member_organization() FROM PUBLIC;

CREATE TRIGGER project_member_organization_check
    BEFORE INSERT OR UPDATE OF project_role, user_id, project_id ON prj.project_member
    FOR EACH ROW EXECUTE FUNCTION prj.check_member_organization();
