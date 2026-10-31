-- Removes the data seed.sql created (projects coded PERF-nnn). Audit entries written during the load test stay:
-- the audit log is append-only by design.
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE perf_project ON COMMIT DROP AS SELECT id FROM prj.project WHERE code LIKE 'PERF-%';

DELETE FROM dr.document_version WHERE project_id IN (SELECT id FROM perf_project);
DELETE FROM dr.document WHERE project_id IN (SELECT id FROM perf_project);
DELETE FROM dr.folder WHERE project_id IN (SELECT id FROM perf_project);
DELETE FROM qna.question WHERE project_id IN (SELECT id FROM perf_project);
DELETE FROM fnd.finding WHERE project_id IN (SELECT id FROM perf_project);
DELETE FROM req.request_item WHERE project_id IN (SELECT id FROM perf_project);
DELETE FROM prj.project WHERE id IN (SELECT id FROM perf_project); -- workstreams and members cascade
COMMIT;
