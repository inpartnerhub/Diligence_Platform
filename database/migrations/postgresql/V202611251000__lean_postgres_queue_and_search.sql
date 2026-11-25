-- Lean PostgreSQL ingestion queue and full-text search index, replacing RabbitMQ and OpenSearch.

CREATE TABLE dr.ingestion_job
(
    id                    UUID PRIMARY KEY,
    project_id            UUID        NOT NULL,
    document_id           UUID        NOT NULL,
    version_id            UUID        NOT NULL,
    object_key            TEXT        NOT NULL,
    file_name             TEXT        NOT NULL,
    declared_content_type TEXT        NOT NULL,
    size_bytes            BIGINT      NOT NULL,
    status                TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'FAILED')),
    attempts              INT         NOT NULL DEFAULT 0,
    max_attempts          INT         NOT NULL DEFAULT 3,
    available_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at             TIMESTAMPTZ,
    last_error            TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ingestion_job_claim_idx ON dr.ingestion_job (available_at, created_at) WHERE status = 'PENDING';
CREATE INDEX ingestion_job_processing_idx ON dr.ingestion_job (locked_at) WHERE status = 'PROCESSING';
CREATE INDEX ingestion_job_project_idx ON dr.ingestion_job (project_id);

ALTER TABLE dr.ingestion_job ENABLE ROW LEVEL SECURITY;
CREATE POLICY ingestion_job_enqueue ON dr.ingestion_job FOR INSERT
    WITH CHECK (prj.is_system() OR prj.has_project_role(project_id, ARRAY['PARTNER','PROJECT_MANAGER','ANALYST','TARGET']));
CREATE POLICY ingestion_job_worker ON dr.ingestion_job FOR ALL
    USING (prj.is_system())
    WITH CHECK (prj.is_system());

CREATE TABLE dr.document_search_index
(
    document_id   UUID PRIMARY KEY,
    version_id    UUID        NOT NULL,
    project_id    UUID        NOT NULL,
    folder_id     UUID        NOT NULL,
    name          TEXT        NOT NULL,
    content_type  TEXT,
    content       TEXT        NOT NULL DEFAULT '',
    uploaded_at   TIMESTAMPTZ NOT NULL,
    indexed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    search_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(name, '')), 'A')
            || setweight(to_tsvector('indonesian', coalesce(name, '')), 'A')
            || setweight(to_tsvector('simple', coalesce(content, '')), 'B')
            || setweight(to_tsvector('indonesian', coalesce(content, '')), 'C')
            || setweight(to_tsvector('english', coalesce(content, '')), 'C')
    ) STORED
);

CREATE INDEX document_search_index_vector_idx ON dr.document_search_index USING GIN (search_vector);
CREATE INDEX document_search_index_project_folder_idx ON dr.document_search_index (project_id, folder_id);
CREATE INDEX document_search_index_indexed_at_idx ON dr.document_search_index (indexed_at);

ALTER TABLE dr.document_search_index ENABLE ROW LEVEL SECURITY;
CREATE POLICY document_search_index_read ON dr.document_search_index FOR SELECT
    USING (prj.is_system() OR prj.has_project_role(project_id, ARRAY['PARTNER','PROJECT_MANAGER','ANALYST','TARGET','OBSERVER']));
CREATE POLICY document_search_index_write ON dr.document_search_index FOR ALL
    USING (prj.is_system())
    WITH CHECK (prj.is_system());

GRANT SELECT, INSERT, UPDATE, DELETE ON dr.ingestion_job, dr.document_search_index TO diligence_app;
