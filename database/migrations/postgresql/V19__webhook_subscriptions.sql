-- Sprint Phase 3, Outbound webhook dispatcher for platform events (PRD ADM-07).
-- Real-time event notifications with HMAC-SHA256 signature verification, exponential backoff retries, and delivery logging.

CREATE TABLE adm.webhook_subscription
(
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID REFERENCES adm.organization (id) ON DELETE CASCADE,
    target_url        TEXT        NOT NULL,
    signing_key       TEXT        NOT NULL,
    description       TEXT,
    subscribed_events TEXT        NOT NULL DEFAULT '*',
    active            BOOLEAN     NOT NULL DEFAULT true,
    created_by        UUID        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX webhook_subscription_org_idx ON adm.webhook_subscription (organization_id);
CREATE INDEX webhook_subscription_active_idx ON adm.webhook_subscription (active);

CREATE TABLE adm.webhook_delivery_log
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID        NOT NULL REFERENCES adm.webhook_subscription (id) ON DELETE CASCADE,
    event_type      TEXT        NOT NULL,
    payload         TEXT        NOT NULL,
    status_code     INT,
    success         BOOLEAN     NOT NULL,
    attempt_count   INT         NOT NULL DEFAULT 1,
    error_message   TEXT,
    delivered_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    duration_ms     BIGINT
);

CREATE INDEX webhook_delivery_log_sub_idx ON adm.webhook_delivery_log (subscription_id, delivered_at DESC);
CREATE INDEX webhook_delivery_log_event_idx ON adm.webhook_delivery_log (event_type);

ALTER TABLE adm.webhook_subscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE adm.webhook_delivery_log ENABLE ROW LEVEL SECURITY;

CREATE POLICY webhook_subscription_read ON adm.webhook_subscription FOR SELECT USING (
    prj.is_system() OR prj.app_role() IN ('admin', 'partner'));

CREATE POLICY webhook_subscription_write ON adm.webhook_subscription FOR ALL
    USING (prj.is_system() OR prj.app_role() IN ('admin', 'partner'))
    WITH CHECK (prj.is_system() OR prj.app_role() IN ('admin', 'partner'));

CREATE POLICY webhook_delivery_log_read ON adm.webhook_delivery_log FOR SELECT USING (
    prj.is_system() OR prj.app_role() IN ('admin', 'partner'));

CREATE POLICY webhook_delivery_log_write ON adm.webhook_delivery_log FOR ALL
    USING (prj.is_system() OR prj.app_role() IN ('admin', 'partner'))
    WITH CHECK (prj.is_system() OR prj.app_role() IN ('admin', 'partner'));
