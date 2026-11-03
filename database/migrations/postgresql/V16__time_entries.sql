-- Phase 3: Time and workload tracking per member and workstream (PRD PRJ-06).

CREATE TABLE prj.time_entry
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID           NOT NULL REFERENCES prj.project (id) ON DELETE CASCADE,
    workstream_type TEXT           NOT NULL CHECK (workstream_type IN ('FINANCIAL', 'TAX', 'LEGAL', 'COMMERCIAL',
                                                                      'OPERATIONAL', 'HR', 'IT_CYBER', 'ESG_COMPLIANCE')),
    user_id         UUID           NOT NULL,
    entry_date      DATE           NOT NULL,
    hours           NUMERIC(4, 2)  NOT NULL CHECK (hours > 0 AND hours <= 24),
    description     TEXT,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX time_entry_project_date_idx ON prj.time_entry (project_id, entry_date);
CREATE INDEX time_entry_user_date_idx ON prj.time_entry (user_id, entry_date);

ALTER TABLE prj.time_entry ENABLE ROW LEVEL SECURITY;

CREATE POLICY time_entry_read ON prj.time_entry FOR SELECT USING (
    prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER', 'PROJECT_MANAGER', 'ANALYST', 'OBSERVER'])
);

CREATE POLICY time_entry_insert ON prj.time_entry FOR INSERT WITH CHECK (
    prj.is_system() OR (
        prj.has_project_role(project_id, ARRAY ['PARTNER', 'PROJECT_MANAGER', 'ANALYST'])
        AND (user_id = prj.app_user() OR prj.can_manage(project_id))
    )
);

CREATE POLICY time_entry_update ON prj.time_entry FOR UPDATE USING (
    prj.is_system() OR (
        prj.has_project_role(project_id, ARRAY ['PARTNER', 'PROJECT_MANAGER', 'ANALYST'])
        AND (user_id = prj.app_user() OR prj.can_manage(project_id))
    )
);

CREATE POLICY time_entry_delete ON prj.time_entry FOR DELETE USING (
    prj.is_system() OR (
        prj.has_project_role(project_id, ARRAY ['PARTNER', 'PROJECT_MANAGER', 'ANALYST'])
        AND (user_id = prj.app_user() OR prj.can_manage(project_id))
    )
);
