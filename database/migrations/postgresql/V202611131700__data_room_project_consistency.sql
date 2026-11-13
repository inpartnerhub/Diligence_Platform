-- #181 (audit AUD-16): a folder's parent, a document's folder and a version's document must belong to the same
-- project. Until now only application code checked this; the row-level security policies did not
-- (folder_create_on_upload let an analyst attach a folder to another project's parent, and the document_version
-- policies never compared project_id with the document's).
--
-- Composite foreign keys instead of extra WITH CHECK predicates: they hold for every database role, including the
-- owner and the RLS `system` context, and are checked without being filtered by the policies. The existing
-- single-column foreign keys stay; the composite ones add the project to the match. Adding them validates every
-- existing row, so the migration fails if inconsistent rows already exist.

ALTER TABLE dr.folder ADD CONSTRAINT folder_project_id_uq UNIQUE (project_id, id);
ALTER TABLE dr.document ADD CONSTRAINT document_project_id_uq UNIQUE (project_id, id);

-- A subfolder lives in its parent's project (MATCH SIMPLE: root folders have no parent).
ALTER TABLE dr.folder ADD CONSTRAINT folder_parent_same_project_fk
    FOREIGN KEY (project_id, parent_id) REFERENCES dr.folder (project_id, id) ON DELETE CASCADE;

-- A document lives in its folder's project.
ALTER TABLE dr.document ADD CONSTRAINT document_folder_same_project_fk
    FOREIGN KEY (project_id, folder_id) REFERENCES dr.folder (project_id, id);

-- A version carries its document's project_id (the RLS policies and the purge rely on it).
ALTER TABLE dr.document_version ADD CONSTRAINT document_version_document_same_project_fk
    FOREIGN KEY (project_id, document_id) REFERENCES dr.document (project_id, id) ON DELETE CASCADE;
