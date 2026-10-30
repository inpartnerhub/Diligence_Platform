-- PRD Bab 9 core entities. Every project-scoped table carries project_id (denormalized where the PRD relates it
-- through a parent) so row-level security can filter each table on its own (V5).
-- Cross-module references (e.g. to adm.user_account or dr.document from another schema) are plain UUIDs.

-- ---------------------------------------------------------------- prj: projects (PRJ)
CREATE TABLE prj.project
(
    id                     UUID PRIMARY KEY,
    code                   TEXT        NOT NULL,
    name                   TEXT        NOT NULL,
    client_organization_id UUID        NOT NULL,
    target_organization_id UUID        NOT NULL,
    stage                  TEXT        NOT NULL DEFAULT 'INITIATION'
        CHECK (stage IN ('INITIATION', 'REQUEST_LIST', 'DATA_COLLECTION', 'REVIEW', 'CLARIFICATION',
                         'RISK_ASSESSMENT', 'REPORTING', 'CLOSED')),
    status                 TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    start_date             DATE,
    end_date               DATE,
    ai_features_enabled    BOOLEAN     NOT NULL DEFAULT false, -- PRD 10.3: clients can disable AI per project
    created_by             UUID        NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX project_code_uq ON prj.project (lower(code));

CREATE TABLE prj.workstream
(
    id           UUID PRIMARY KEY,
    project_id   UUID        NOT NULL REFERENCES prj.project (id) ON DELETE CASCADE,
    type         TEXT        NOT NULL CHECK (type IN ('FINANCIAL', 'TAX', 'LEGAL', 'COMMERCIAL', 'OPERATIONAL',
                                                      'HR', 'IT_CYBER', 'ESG_COMPLIANCE')),
    lead_user_id UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, type)
);

-- Per-project (and optionally per-workstream) role; the basis of every project-scoped RLS policy.
CREATE TABLE prj.project_member
(
    id                UUID PRIMARY KEY,
    project_id        UUID        NOT NULL REFERENCES prj.project (id) ON DELETE CASCADE,
    user_id           UUID        NOT NULL,
    project_role      TEXT        NOT NULL CHECK (project_role IN ('PARTNER', 'PROJECT_MANAGER', 'ANALYST',
                                                                    'CLIENT', 'TARGET', 'OBSERVER')),
    workstream_id     UUID REFERENCES prj.workstream (id) ON DELETE CASCADE,
    access_expires_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX project_member_uq ON prj.project_member (project_id, user_id, coalesce(workstream_id, '00000000-0000-0000-0000-000000000000'));
CREATE INDEX project_member_user_idx ON prj.project_member (user_id, project_id);

-- PRJ-03: reusable templates (workstreams, request list, finding categories, folder structure) — global.
CREATE TABLE prj.project_template
(
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL,
    definition JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version    BIGINT      NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------- req: request list (REQ)
CREATE TABLE req.request_item
(
    id               UUID PRIMARY KEY,
    project_id       UUID        NOT NULL,
    workstream_id    UUID        NOT NULL,
    number           TEXT        NOT NULL,
    category         TEXT        NOT NULL,
    description      TEXT        NOT NULL,
    priority         TEXT        NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    due_date         DATE,
    status           TEXT        NOT NULL DEFAULT 'NOT_REQUESTED'
        CHECK (status IN ('NOT_REQUESTED', 'REQUESTED', 'PARTIAL', 'RECEIVED', 'REJECTED', 'CLOSED')),
    assignee_user_id UUID,
    wave             SMALLINT    NOT NULL DEFAULT 1, -- REQ-06 staged release
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0,
    UNIQUE (project_id, number)
);
CREATE INDEX request_item_workstream_idx ON req.request_item (project_id, workstream_id, status);

CREATE TABLE req.request_item_document
(
    request_item_id UUID        NOT NULL REFERENCES req.request_item (id) ON DELETE CASCADE,
    document_id     UUID        NOT NULL,
    project_id      UUID        NOT NULL,
    comment         TEXT,
    linked_by       UUID        NOT NULL,
    linked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (request_item_id, document_id)
);

-- ---------------------------------------------------------------- dr: data room (DOC)
CREATE TABLE dr.folder
(
    id               UUID PRIMARY KEY,
    project_id       UUID        NOT NULL,
    parent_id        UUID REFERENCES dr.folder (id) ON DELETE CASCADE,
    name             TEXT        NOT NULL,
    download_allowed BOOLEAN     NOT NULL DEFAULT false, -- DOC-05: deny by default
    print_allowed    BOOLEAN     NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX folder_project_idx ON dr.folder (project_id, parent_id);

CREATE TABLE dr.document
(
    id                 UUID PRIMARY KEY,
    project_id         UUID        NOT NULL,
    folder_id          UUID        NOT NULL REFERENCES dr.folder (id),
    name               TEXT        NOT NULL,
    content_type       TEXT        NOT NULL,
    size_bytes         BIGINT      NOT NULL,
    sha256             TEXT        NOT NULL,
    current_version_id UUID,
    scan_status        TEXT        NOT NULL DEFAULT 'PENDING'
        CHECK (scan_status IN ('PENDING', 'CLEAN', 'INFECTED', 'FAILED')),
    extracted_text     TEXT, -- OCR / extraction output; the search index is the query path
    created_by         UUID        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    version            BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX document_folder_idx ON dr.document (project_id, folder_id);
CREATE INDEX document_hash_idx ON dr.document (project_id, sha256); -- duplicate detection

CREATE TABLE dr.document_version
(
    id             UUID PRIMARY KEY,
    document_id    UUID        NOT NULL REFERENCES dr.document (id) ON DELETE CASCADE,
    project_id     UUID        NOT NULL,
    version_number INT         NOT NULL,
    object_key     TEXT        NOT NULL,
    preview_key    TEXT,
    size_bytes     BIGINT      NOT NULL,
    sha256         TEXT        NOT NULL,
    scan_status    TEXT        NOT NULL DEFAULT 'PENDING'
        CHECK (scan_status IN ('PENDING', 'CLEAN', 'INFECTED', 'FAILED')),
    uploaded_by    UUID        NOT NULL,
    uploaded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, version_number)
);

-- ---------------------------------------------------------------- qna: questions (QNA)
CREATE TABLE qna.question
(
    id                UUID PRIMARY KEY,
    project_id        UUID        NOT NULL,
    request_item_id   UUID,
    document_id       UUID,
    asked_by          UUID        NOT NULL,
    recipient_user_id UUID,
    body              TEXT        NOT NULL,
    answer            TEXT,
    answered_by       UUID,
    answered_at       TIMESTAMPTZ,
    status            TEXT        NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'SENT', 'ANSWERED', 'CLOSED')),
    due_date          DATE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX question_project_idx ON qna.question (project_id, status);

-- ---------------------------------------------------------------- fnd: findings (FND)
CREATE TABLE fnd.finding
(
    id                 UUID PRIMARY KEY,
    project_id         UUID          NOT NULL,
    workstream_id      UUID          NOT NULL,
    title              TEXT          NOT NULL,
    category           TEXT          NOT NULL,
    description        TEXT          NOT NULL,
    impact_description TEXT,
    monetary_impact    NUMERIC(19, 2),
    currency           CHAR(3),
    recommendation     TEXT,
    impact             SMALLINT CHECK (impact BETWEEN 1 AND 5),
    likelihood         SMALLINT CHECK (likelihood BETWEEN 1 AND 5),
    risk_score         SMALLINT CHECK (risk_score BETWEEN 1 AND 25),
    risk_level         TEXT CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status             TEXT          NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'RELEASED', 'ARCHIVED')), -- FND-04
    created_by         UUID          NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX finding_project_idx ON fnd.finding (project_id, workstream_id, status);

-- FND-02: link findings to source documents (with page/section) and to Q&A answers.
CREATE TABLE fnd.finding_document
(
    finding_id     UUID NOT NULL REFERENCES fnd.finding (id) ON DELETE CASCADE,
    document_id    UUID NOT NULL,
    project_id     UUID NOT NULL,
    page_reference TEXT,
    PRIMARY KEY (finding_id, document_id)
);

CREATE TABLE fnd.finding_question
(
    finding_id  UUID NOT NULL REFERENCES fnd.finding (id) ON DELETE CASCADE,
    question_id UUID NOT NULL,
    project_id  UUID NOT NULL,
    PRIMARY KEY (finding_id, question_id)
);

-- FND-03: admin-configurable thresholds (Appendix A defaults). Single row.
CREATE TABLE fnd.risk_matrix_config
(
    id            SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    medium_from   SMALLINT    NOT NULL,
    high_from     SMALLINT    NOT NULL,
    critical_from SMALLINT    NOT NULL,
    updated_by    UUID,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (1 < medium_from AND medium_from < high_from AND high_from < critical_from AND critical_from <= 25)
);
INSERT INTO fnd.risk_matrix_config (medium_from, high_from, critical_from) VALUES (5, 10, 17);

-- ---------------------------------------------------------------- rpt: reports (RPT)
CREATE TABLE rpt.report
(
    id              UUID PRIMARY KEY,
    project_id      UUID        NOT NULL,
    workstream_id   UUID,
    version_number  INT         NOT NULL,
    title           TEXT        NOT NULL,
    approval_status TEXT        NOT NULL DEFAULT 'DRAFT'
        CHECK (approval_status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'ISSUED')),
    object_key      TEXT,
    created_by      UUID        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE rpt.report_finding
(
    report_id  UUID NOT NULL REFERENCES rpt.report (id) ON DELETE CASCADE,
    finding_id UUID NOT NULL,
    project_id UUID NOT NULL,
    position   INT  NOT NULL,
    PRIMARY KEY (report_id, finding_id)
);
