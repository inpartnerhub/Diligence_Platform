-- Sprint 9–10, request list (PRD REQ-01…REQ-06) and notifications (ADM-05, REQ-04).
--   * status history per item (KPI "kick-off → 80% of the request list fulfilled", REQ-06 wave history)
--   * comment threads per item; internal notes are never visible to the target side (REQ-03)
--   * reminder bookkeeping and in-app notifications
--   * row-level security: the target only sees released items (not NOT_REQUESTED), never creates, deletes, hides or
--     closes items; scheduled jobs are coordinated across replicas with ShedLock

-- ---------------------------------------------------------------- req: schema
ALTER TABLE req.request_item
    ADD COLUMN requested_at     TIMESTAMPTZ,
    ADD COLUMN updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN last_reminded_at DATE;
CREATE INDEX request_item_due_idx ON req.request_item (due_date)
    WHERE status IN ('REQUESTED', 'PARTIAL', 'REJECTED');

CREATE TABLE req.request_item_status_history
(
    id              UUID PRIMARY KEY,
    request_item_id UUID        NOT NULL REFERENCES req.request_item (id) ON DELETE CASCADE,
    project_id      UUID        NOT NULL,
    from_status     TEXT,
    to_status       TEXT        NOT NULL,
    wave            SMALLINT    NOT NULL,
    changed_by      UUID,
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    note            TEXT
);
CREATE INDEX request_item_status_history_item_idx ON req.request_item_status_history (request_item_id, changed_at);
CREATE INDEX request_item_status_history_project_idx ON req.request_item_status_history (project_id, changed_at);

CREATE TABLE req.request_item_comment
(
    id              UUID PRIMARY KEY,
    request_item_id UUID        NOT NULL REFERENCES req.request_item (id) ON DELETE CASCADE,
    project_id      UUID        NOT NULL,
    author_id       UUID        NOT NULL,
    body            TEXT        NOT NULL CHECK (length(body) BETWEEN 1 AND 4000),
    internal        BOOLEAN     NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX request_item_comment_item_idx ON req.request_item_comment (request_item_id, created_at);

-- Items created before V9 that were already released count as requested at creation time.
UPDATE req.request_item SET requested_at = created_at WHERE status <> 'NOT_REQUESTED';

-- ---------------------------------------------------------------- req: row-level security
DROP POLICY request_item_read ON req.request_item;
DROP POLICY request_item_write ON req.request_item;

-- The internal team and the client see every item (progress); the target only sees items released to it.
CREATE POLICY request_item_read ON req.request_item FOR SELECT USING (
    prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','CLIENT','OBSERVER'])
        OR (status <> 'NOT_REQUESTED' AND prj.has_project_role(project_id, ARRAY ['TARGET'])));
CREATE POLICY request_item_insert ON req.request_item FOR INSERT WITH CHECK (
    prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']));
CREATE POLICY request_item_delete ON req.request_item FOR DELETE USING (
    prj.is_system() OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST']));
-- The target updates released items only (delivery status, last change), and can neither hide nor close them.
CREATE POLICY request_item_update ON req.request_item FOR UPDATE
    USING (prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])
        OR (status <> 'NOT_REQUESTED' AND prj.has_project_role(project_id, ARRAY ['TARGET'])))
    WITH CHECK (prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])
        OR (status IN ('REQUESTED', 'PARTIAL', 'RECEIVED', 'REJECTED')
            AND prj.has_project_role(project_id, ARRAY ['TARGET'])));

-- Item visibility is the barrier for its children: the target only reaches released items.
CREATE FUNCTION req.item_visible(p_item_id uuid) RETURNS boolean
    LANGUAGE sql STABLE AS
$$ SELECT EXISTS (SELECT 1 FROM req.request_item i WHERE i.id = p_item_id) $$;

DROP POLICY request_item_document_access ON req.request_item_document;
CREATE POLICY request_item_document_read ON req.request_item_document FOR SELECT USING (
    prj.is_system()
        OR (prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','TARGET','OBSERVER'])
            AND req.item_visible(request_item_id)));
CREATE POLICY request_item_document_write ON req.request_item_document FOR ALL
    USING (prj.is_system()
        OR (prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','TARGET'])
            AND req.item_visible(request_item_id)))
    WITH CHECK (prj.is_system()
        OR (prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','TARGET'])
            AND req.item_visible(request_item_id) AND linked_by = prj.app_user()));

ALTER TABLE req.request_item_status_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY request_item_status_history_read ON req.request_item_status_history FOR SELECT USING (
    prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','CLIENT','OBSERVER'])
        OR (prj.has_project_role(project_id, ARRAY ['TARGET']) AND req.item_visible(request_item_id)));
CREATE POLICY request_item_status_history_insert ON req.request_item_status_history FOR INSERT WITH CHECK (
    prj.is_system()
        OR (prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','TARGET'])
            AND changed_by = prj.app_user()));

-- Internal notes (internal = true) are part of the information barrier: the target never reads them.
ALTER TABLE req.request_item_comment ENABLE ROW LEVEL SECURITY;
CREATE POLICY request_item_comment_read ON req.request_item_comment FOR SELECT USING (
    prj.is_system()
        OR prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST','OBSERVER'])
        OR (NOT internal AND prj.has_project_role(project_id, ARRAY ['TARGET'])
            AND req.item_visible(request_item_id)));
CREATE POLICY request_item_comment_insert ON req.request_item_comment FOR INSERT WITH CHECK (
    prj.is_system()
        OR (author_id = prj.app_user()
            AND (prj.has_project_role(project_id, ARRAY ['PARTNER','PROJECT_MANAGER','ANALYST'])
                OR (NOT internal AND prj.has_project_role(project_id, ARRAY ['TARGET'])
                    AND req.item_visible(request_item_id)))));

-- ---------------------------------------------------------------- ntf: in-app notifications (ADM-05)
CREATE TABLE ntf.notification
(
    id                UUID PRIMARY KEY,
    user_id           UUID        NOT NULL,
    notification_type TEXT        NOT NULL,
    project_id        UUID,
    params            JSONB       NOT NULL DEFAULT '{}',
    link              TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at           TIMESTAMPTZ
);
CREATE INDEX notification_user_idx ON ntf.notification (user_id, created_at DESC);
CREATE INDEX notification_unread_idx ON ntf.notification (user_id) WHERE read_at IS NULL;

-- Notifications are created by background listeners (system) and only ever read by their recipient.
ALTER TABLE ntf.notification ENABLE ROW LEVEL SECURITY;
CREATE POLICY notification_owner ON ntf.notification FOR SELECT USING (prj.is_system() OR user_id = prj.app_user());
CREATE POLICY notification_insert ON ntf.notification FOR INSERT WITH CHECK (prj.is_system());
CREATE POLICY notification_mark_read ON ntf.notification FOR UPDATE
    USING (prj.is_system() OR user_id = prj.app_user())
    WITH CHECK (prj.is_system() OR user_id = prj.app_user());
CREATE POLICY notification_delete ON ntf.notification FOR DELETE USING (prj.is_system());

ALTER TABLE ntf.notification_preference ENABLE ROW LEVEL SECURITY;
CREATE POLICY notification_preference_owner ON ntf.notification_preference FOR ALL
    USING (prj.is_system() OR user_id = prj.app_user())
    WITH CHECK (prj.is_system() OR user_id = prj.app_user());

-- ---------------------------------------------------------------- scheduled jobs (reminders) across replicas
CREATE TABLE public.shedlock
(
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
GRANT SELECT, INSERT, UPDATE, DELETE ON public.shedlock TO diligence_app;
