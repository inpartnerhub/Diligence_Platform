-- PRD ADM-03: audit trail that cannot be changed — who, when, from where, what, outcome.
-- Enforced twice: the runtime role only has SELECT/INSERT (V1 default privileges), and triggers reject
-- UPDATE, DELETE and TRUNCATE for every role, including the owner.

CREATE TABLE audit.audit_log
(
    id          UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id     UUID,
    action      TEXT        NOT NULL,
    object_type TEXT        NOT NULL,
    object_id   TEXT,
    ip_address  TEXT,
    user_agent  TEXT,
    outcome     TEXT        NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE')),
    details     JSONB       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX audit_log_occurred_idx ON audit.audit_log (occurred_at DESC);
CREATE INDEX audit_log_user_idx ON audit.audit_log (user_id, occurred_at DESC);
CREATE INDEX audit_log_object_idx ON audit.audit_log (object_type, object_id, occurred_at DESC);

CREATE FUNCTION audit.reject_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit.audit_log is append-only (% rejected)', TG_OP USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit.audit_log
    FOR EACH ROW EXECUTE FUNCTION audit.reject_modification();
CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_modification();
