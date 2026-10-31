-- Synthetic load-test data: projects coded PERF-nnn with workstreams, request items, findings, questions, folders and
-- documents, all managed by one user (:member). Run through seed.sh, which also feeds the documents to the search
-- index. Remove everything again with cleanup.sql. Runs as the database owner, so row-level security does not apply.
\set ON_ERROR_STOP on
BEGIN;

CREATE TEMP TABLE perf_project ON COMMIT DROP AS
SELECT gen_random_uuid() AS id, n FROM generate_series(1, :projects) AS n;

INSERT INTO prj.project (id, code, name, client_organization_id, target_organization_id, created_by)
SELECT p.id, 'PERF-' || lpad(p.n::text, 3, '0'), 'Performance project ' || p.n,
       coalesce((SELECT id FROM adm.organization WHERE type = 'CLIENT' ORDER BY created_at LIMIT 1), gen_random_uuid()),
       coalesce((SELECT id FROM adm.organization WHERE type = 'TARGET' ORDER BY created_at LIMIT 1), gen_random_uuid()),
       :'member'
FROM perf_project p;

INSERT INTO prj.project_member (id, project_id, user_id, project_role)
SELECT gen_random_uuid(), id, :'member', 'PROJECT_MANAGER' FROM perf_project;

CREATE TEMP TABLE perf_workstream ON COMMIT DROP AS
SELECT gen_random_uuid() AS id, p.id AS project_id, w.type, w.ord::int AS ord
FROM perf_project p
         CROSS JOIN unnest(ARRAY ['LEGAL', 'FINANCIAL', 'TAX', 'COMMERCIAL']) WITH ORDINALITY AS w(type, ord);
INSERT INTO prj.workstream (id, project_id, type) SELECT id, project_id, type FROM perf_workstream;

-- Request items across the six statuses, due dates spread around today.
INSERT INTO req.request_item (id, project_id, workstream_id, number, category, description, priority, status,
                              due_date, wave)
SELECT gen_random_uuid(), p.id, w.id, 'PRF-' || lpad(i::text, 4, '0'), 'Category ' || (i % 12),
       'Please provide document set ' || i || ' for the ' || lower(w.type) || ' review',
       (ARRAY ['LOW', 'MEDIUM', 'HIGH'])[i % 3 + 1],
       (ARRAY ['NOT_REQUESTED', 'REQUESTED', 'PARTIAL', 'RECEIVED', 'REJECTED', 'CLOSED'])[i % 6 + 1],
       current_date + (i % 60 - 20), i % 3 + 1
FROM perf_project p
         CROSS JOIN generate_series(1, :items) AS i
         JOIN perf_workstream w ON w.project_id = p.id AND w.ord = i % 4 + 1;

-- Findings rated on the 5 × 5 matrix in every workflow status.
INSERT INTO fnd.finding (id, project_id, workstream_id, number, title, category, description, recommendation, impact,
                         likelihood, risk_score, risk_level, status, created_by)
SELECT gen_random_uuid(), p.id, w.id, 'F-PRF-' || lpad(i::text, 4, '0'), 'Finding ' || i, 'Category ' || (i % 8),
       'Observed issue ' || i || ' in the ' || lower(w.type) || ' workstream', 'Mitigate issue ' || i,
       r.impact, r.likelihood, r.impact * r.likelihood,
       CASE WHEN r.impact * r.likelihood <= 4 THEN 'LOW' WHEN r.impact * r.likelihood <= 9 THEN 'MEDIUM'
            WHEN r.impact * r.likelihood <= 16 THEN 'HIGH' ELSE 'CRITICAL' END,
       (ARRAY ['DRAFT', 'IN_REVIEW', 'APPROVED', 'RELEASED'])[i % 4 + 1], :'member'
FROM perf_project p
         CROSS JOIN generate_series(1, :findings) AS i
         CROSS JOIN LATERAL (SELECT i % 5 + 1 AS impact, (i * 7) % 5 + 1 AS likelihood) AS r
         JOIN perf_workstream w ON w.project_id = p.id AND w.ord = i % 4 + 1;

INSERT INTO qna.question (id, project_id, workstream_id, number, asked_by, body, status)
SELECT gen_random_uuid(), p.id, w.id, 'Q-' || lpad(i::text, 4, '0'), :'member',
       'Question ' || i || ' about the ' || lower(w.type) || ' documents',
       (ARRAY ['DRAFT', 'PENDING_APPROVAL', 'SENT', 'CLOSED'])[i % 4 + 1]
FROM perf_project p
         CROSS JOIN generate_series(1, :questions) AS i
         JOIN perf_workstream w ON w.project_id = p.id AND w.ord = i % 4 + 1;

-- One folder per workstream; documents are clean (scanned) PDFs without stored files or previews.
CREATE TEMP TABLE perf_folder ON COMMIT DROP AS
SELECT gen_random_uuid() AS id, w.project_id, w.id AS workstream_id, w.type, w.ord FROM perf_workstream w;
INSERT INTO dr.folder (id, project_id, name, workstream_id)
SELECT id, project_id, lpad(ord::text, 2, '0') || ' ' || initcap(type), workstream_id FROM perf_folder;

CREATE TEMP TABLE perf_document ON COMMIT DROP AS
SELECT gen_random_uuid() AS id, gen_random_uuid() AS version_id, p.id AS project_id, f.id AS folder_id, i,
       'Document ' || lpad(i::text, 5, '0') || '.pdf' AS name
FROM perf_project p
         CROSS JOIN generate_series(1, :documents) AS i
         JOIN perf_folder f ON f.project_id = p.id AND f.ord = i % 4 + 1;
INSERT INTO dr.document (id, project_id, folder_id, name, content_type, size_bytes, sha256, scan_status, created_by)
SELECT id, project_id, folder_id, name, 'application/pdf', 100000 + i, md5(id::text), 'CLEAN', :'member'
FROM perf_document;
INSERT INTO dr.document_version (id, document_id, project_id, version_number, object_key, file_name, content_type,
                                 size_bytes, sha256, uploaded_by, scan_status)
SELECT version_id, id, project_id, 1, 'perf/' || id, name, 'application/pdf', 100000 + i, md5(id::text), :'member',
       'CLEAN'
FROM perf_document;
UPDATE dr.document d SET current_version_id = pd.version_id FROM perf_document pd WHERE d.id = pd.id;

COMMIT;
