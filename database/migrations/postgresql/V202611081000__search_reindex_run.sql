-- Bulk re-index of the search index from PostgreSQL (issue #80): one row per run, written by the Super Admin who
-- starts it and then by the background worker (system role). The partial unique index allows one running run at a
-- time across replicas; heartbeat_at shows whether it is still alive.

CREATE TABLE dr.search_reindex_run
(
    id              UUID PRIMARY KEY,
    status          TEXT        NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    requested_by    UUID        NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    heartbeat_at    TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    documents_total BIGINT      NOT NULL DEFAULT 0,
    indexed         BIGINT      NOT NULL DEFAULT 0,
    failed          BIGINT      NOT NULL DEFAULT 0,
    removed         BIGINT,
    error           TEXT
);

CREATE UNIQUE INDEX search_reindex_run_one_running ON dr.search_reindex_run (status) WHERE status = 'RUNNING';
CREATE INDEX search_reindex_run_started_idx ON dr.search_reindex_run (started_at DESC);

ALTER TABLE dr.search_reindex_run ENABLE ROW LEVEL SECURITY;

CREATE POLICY search_reindex_run_all ON dr.search_reindex_run FOR ALL
    USING (prj.is_system() OR prj.app_role() = 'super_admin')
    WITH CHECK (prj.is_system() OR prj.app_role() = 'super_admin');
