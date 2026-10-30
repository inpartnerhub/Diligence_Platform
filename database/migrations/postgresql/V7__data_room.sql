-- Sprint 5–6, data room (PRD DOC-01, DOC-02, DOC-07).
--   * per-folder access grants (by project role, user or group), inherited by subfolders
--   * folders optionally belong to a workstream (analysts only see their workstreams' folders)
--   * version metadata for the ingestion pipeline (detected type, scan result) and the REJECTED status
--   * row-level security: the target side only sees and uploads into folders granted to it (PRD Bab 3)

-- ---------------------------------------------------------------- schema
ALTER TABLE dr.folder ADD COLUMN workstream_id UUID;
CREATE UNIQUE INDEX folder_name_uq
    ON dr.folder (project_id, coalesce(parent_id, '00000000-0000-0000-0000-000000000000'), lower(name));

-- One document per name and folder; uploading the same name again adds a version (DOC-07).
CREATE UNIQUE INDEX document_name_uq ON dr.document (folder_id, lower(name));

ALTER TABLE dr.document DROP CONSTRAINT document_scan_status_check;
ALTER TABLE dr.document ADD CONSTRAINT document_scan_status_check
    CHECK (scan_status IN ('PENDING', 'CLEAN', 'INFECTED', 'REJECTED', 'FAILED'));

ALTER TABLE dr.document_version DROP CONSTRAINT document_version_scan_status_check;
ALTER TABLE dr.document_version ADD CONSTRAINT document_version_scan_status_check
    CHECK (scan_status IN ('PENDING', 'CLEAN', 'INFECTED', 'REJECTED', 'FAILED'));
ALTER TABLE dr.document_version
    ADD COLUMN file_name             TEXT,
    ADD COLUMN content_type          TEXT,
    ADD COLUMN detected_content_type TEXT,
    ADD COLUMN scan_detail           TEXT,
    ADD COLUMN scanned_at            TIMESTAMPTZ;
UPDATE dr.document_version v SET file_name = d.name, content_type = d.content_type
FROM dr.document d WHERE d.id = v.document_id;
ALTER TABLE dr.document_version
    ALTER COLUMN file_name SET NOT NULL,
    ALTER COLUMN content_type SET NOT NULL;

CREATE TABLE dr.folder_access
(
    id             UUID PRIMARY KEY,
    project_id     UUID        NOT NULL,
    folder_id      UUID        NOT NULL REFERENCES dr.folder (id) ON DELETE CASCADE,
    principal_type TEXT        NOT NULL CHECK (principal_type IN ('ROLE', 'USER', 'GROUP')),
    project_role   TEXT CHECK (project_role = 'TARGET'), -- the internal team sees every folder anyway
    principal_id   UUID,
    permission     TEXT        NOT NULL CHECK (permission IN ('VIEW', 'UPLOAD')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((principal_type = 'ROLE' AND project_role IS NOT NULL AND principal_id IS NULL)
        OR (principal_type <> 'ROLE' AND principal_id IS NOT NULL AND project_role IS NULL))
);
CREATE UNIQUE INDEX folder_access_uq ON dr.folder_access
    (folder_id, principal_type, coalesce(project_role, ''),
     coalesce(principal_id, '00000000-0000-0000-0000-000000000000'));

-- ---------------------------------------------------------------- access functions
-- SECURITY DEFINER: walks the folder tree and reads grants and group membership as the owner, so policies on
-- these tables do not recurse. A grant on a folder applies to all of its subfolders.
CREATE FUNCTION dr.has_folder_access(p_folder_id uuid, p_upload boolean) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, dr AS
$$
WITH RECURSIVE ancestors (id, parent_id) AS (
    SELECT f.id, f.parent_id FROM dr.folder f WHERE f.id = p_folder_id
    UNION ALL
    SELECT f.id, f.parent_id FROM dr.folder f JOIN ancestors a ON f.id = a.parent_id
)
SELECT EXISTS (
    SELECT 1
    FROM dr.folder_access fa
    WHERE fa.folder_id IN (SELECT id FROM ancestors)
      AND (NOT p_upload OR fa.permission = 'UPLOAD')
      AND ((fa.principal_type = 'ROLE' AND prj.has_project_role(fa.project_id, ARRAY [fa.project_role]))
        OR (fa.principal_type = 'USER' AND fa.principal_id = prj.app_user()
            AND prj.has_project_role(fa.project_id, ARRAY ['TARGET','ANALYST','OBSERVER','PARTNER','PROJECT_MANAGER']))
        OR (fa.principal_type = 'GROUP'
            AND EXISTS (SELECT 1 FROM adm.group_member gm
                        WHERE gm.group_id = fa.principal_id AND gm.user_id = prj.app_user())
            AND prj.has_project_role(fa.project_id, ARRAY ['TARGET','ANALYST','OBSERVER','PARTNER','PROJECT_MANAGER'])))
)
$$;
REVOKE ALL ON FUNCTION dr.has_folder_access(uuid, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dr.has_folder_access(uuid, boolean) TO diligence_app;
GRANT USAGE ON SCHEMA dr TO diligence_app;

-- ---------------------------------------------------------------- policies
DROP POLICY data_room_read ON dr.folder;
DROP POLICY data_room_write ON dr.folder;
DROP POLICY data_room_read ON dr.document;
DROP POLICY data_room_write ON dr.document;
DROP POLICY data_room_read ON dr.document_version;
DROP POLICY data_room_write ON dr.document_version;

-- Folders: the internal team sees all; the target side only granted folders (and their subfolders).
CREATE POLICY folder_read ON dr.folder FOR SELECT USING (
    prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER'])
        OR (prj.has_project_role(project_id, ARRAY ['TARGET']) AND dr.has_folder_access(id, false)));
CREATE POLICY folder_manage ON dr.folder FOR ALL
    USING (prj.can_manage(project_id)) WITH CHECK (prj.can_manage(project_id));
-- Folder uploads (DOC-02) create subfolders below a folder the uploader may upload into.
CREATE POLICY folder_create_on_upload ON dr.folder FOR INSERT WITH CHECK (
    parent_id IS NOT NULL
        AND (prj.has_project_role(project_id, ARRAY ['ANALYST'])
            OR (prj.has_project_role(project_id, ARRAY ['TARGET']) AND dr.has_folder_access(parent_id, true))));

-- Documents: same visibility as their folder; uploads by the team or by grantees with UPLOAD permission.
CREATE POLICY document_read ON dr.document FOR SELECT USING (
    prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER'])
        OR (prj.has_project_role(project_id, ARRAY ['TARGET']) AND dr.has_folder_access(folder_id, false)));
CREATE POLICY document_write ON dr.document FOR ALL
    USING (prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])
        OR (prj.has_project_role(project_id, ARRAY ['TARGET']) AND dr.has_folder_access(folder_id, true)))
    WITH CHECK (prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])
        OR (prj.has_project_role(project_id, ARRAY ['TARGET']) AND dr.has_folder_access(folder_id, true)));

-- Versions follow their document (the subquery is itself filtered by document policies).
CREATE POLICY document_version_read ON dr.document_version FOR SELECT USING (
    prj.is_system() OR EXISTS (SELECT 1 FROM dr.document d WHERE d.id = document_id));
CREATE POLICY document_version_write ON dr.document_version FOR ALL
    USING (prj.is_system() OR EXISTS (SELECT 1 FROM dr.document d WHERE d.id = document_id))
    WITH CHECK (prj.is_system() OR EXISTS (SELECT 1 FROM dr.document d WHERE d.id = document_id));

-- Grants are managed by the project's managers and visible to the internal team only.
ALTER TABLE dr.folder_access ENABLE ROW LEVEL SECURITY;
CREATE POLICY folder_access_read ON dr.folder_access FOR SELECT USING (
    prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER']));
CREATE POLICY folder_access_manage ON dr.folder_access FOR ALL
    USING (prj.can_manage(project_id)) WITH CHECK (prj.can_manage(project_id));
