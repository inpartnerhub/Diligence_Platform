-- Issue #116: audit searches by action (e.g. document.scan-infected, project.access-blocked from the runbooks) and by
-- object id alone, newest first, without scanning the whole insert-only audit log. Additive only. On a large
-- production table, build them out of band with CREATE INDEX CONCURRENTLY first; IF NOT EXISTS then skips them.
CREATE INDEX IF NOT EXISTS audit_log_action_idx ON audit.audit_log (action, occurred_at DESC);
CREATE INDEX IF NOT EXISTS audit_log_object_id_idx ON audit.audit_log (object_id, occurred_at DESC);
