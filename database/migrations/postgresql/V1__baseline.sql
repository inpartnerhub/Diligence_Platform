-- Baseline: one schema per application module, the runtime role and the Modulith outbox.
-- Conventions: see database/README.md. Cross-schema foreign keys are not allowed (module ownership).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS adm;    -- admin: organizations, users, groups, invitations
CREATE SCHEMA IF NOT EXISTS audit;  -- immutable audit trail
CREATE SCHEMA IF NOT EXISTS ntf;    -- notification preferences and in-app notifications
CREATE SCHEMA IF NOT EXISTS prj;    -- projects, members, workstreams, templates
CREATE SCHEMA IF NOT EXISTS req;    -- request lists
CREATE SCHEMA IF NOT EXISTS dr;     -- data room: folders, documents, versions
CREATE SCHEMA IF NOT EXISTS fnd;    -- findings and risk register
CREATE SCHEMA IF NOT EXISTS qna;    -- questions and answers
CREATE SCHEMA IF NOT EXISTS rpt;    -- reports

-- Runtime role (ADR-0004). The application logs in as the schema owner but switches to this role for every
-- transaction (SET LOCAL ROLE), so row-level security and the audit log's insert-only grants always apply,
-- even when the login user is a superuser (superusers bypass RLS, members of a NOLOGIN role do not).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'diligence_app') THEN
        CREATE ROLE diligence_app NOLOGIN;
    END IF;
END
$$;
GRANT diligence_app TO CURRENT_USER;

GRANT USAGE ON SCHEMA adm, ntf, prj, req, dr, fnd, qna, rpt, audit, public TO diligence_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA adm, ntf, prj, req, dr, fnd, qna, rpt
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO diligence_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA audit GRANT SELECT, INSERT ON TABLES TO diligence_app;

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
GRANT SELECT, INSERT, UPDATE, DELETE ON public.event_publication TO diligence_app;
