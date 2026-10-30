-- Baseline schema. Conventions (see database/README.md):
--   * one schema per application module; cross-module foreign keys are not allowed
--   * UUID primary keys, timestamptz audit columns, snake_case identifiers

CREATE SCHEMA IF NOT EXISTS document;
CREATE SCHEMA IF NOT EXISTS ingestion;
CREATE SCHEMA IF NOT EXISTS audit;

-- Spring Modulith transactional outbox (event publication registry), schema v2 for PostgreSQL.
CREATE TABLE IF NOT EXISTS public.event_publication
(
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
    ON public.event_publication USING hash (serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx
    ON public.event_publication (completion_date);
