-- #177 (audit AUD-14): SECURITY DEFINER functions run with the schema owner's rights. Unless pg_temp is named in their
-- search_path, PostgreSQL searches the caller's temporary schema first, so a caller could shadow an unqualified table or
-- function with a temporary one. Put pg_temp last in every such function that does not name it yet (the newer ones
-- already do). RuntimeDatabaseLoginIntegrationTest keeps future functions to this rule.

DO
$$
DECLARE
    f record;
BEGIN
    FOR f IN
        SELECT p.oid::regprocedure AS signature, substr(cfg, length('search_path=') + 1) AS path
        FROM pg_proc p
                 JOIN pg_namespace n ON n.oid = p.pronamespace
                 CROSS JOIN LATERAL unnest(p.proconfig) AS cfg
        WHERE p.prosecdef
          AND n.nspname IN ('adm', 'ntf', 'prj', 'req', 'dr', 'fnd', 'qna', 'rpt', 'audit', 'public')
          AND cfg LIKE 'search_path=%'
          AND cfg NOT LIKE '%pg_temp%'
        LOOP
            EXECUTE format('ALTER FUNCTION %s SET search_path = %s, pg_temp', f.signature, f.path);
        END LOOP;
END
$$;
