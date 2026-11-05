-- Issue #58: the risk register, request list and Q&A log are filtered, paged and summarised in SQL.
-- Paging by number uses the existing (project_id, number) unique indexes; these cover the remaining filters and the
-- list-page figures. Additive only.

-- Risk level filter and the heat map per project (FND-03).
CREATE INDEX IF NOT EXISTS finding_project_level_idx ON fnd.finding (project_id, risk_level);

-- Wave filter, waves list and unreleased items per wave (REQ-06).
CREATE INDEX IF NOT EXISTS request_item_project_wave_idx ON req.request_item (project_id, wave, status);

-- Overdue figure: open items by due date within a project (REQ-04).
CREATE INDEX IF NOT EXISTS request_item_project_due_idx ON req.request_item (project_id, due_date)
    WHERE due_date IS NOT NULL;

-- Workstream filter of the Q&A log.
CREATE INDEX IF NOT EXISTS question_project_workstream_idx ON qna.question (project_id, workstream_id);
