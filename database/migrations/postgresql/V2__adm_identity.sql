-- PRD Bab 9: Organization, User (+ groups and invitations, PRD ADM-01).

CREATE TABLE adm.organization
(
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL,
    type       TEXT        NOT NULL CHECK (type IN ('CONSULTANT', 'CLIENT', 'TARGET')),
    status     TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version    BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX organization_name_uq ON adm.organization (lower(name));

-- id is the Keycloak subject; credentials and MFA secrets live only in Keycloak.
CREATE TABLE adm.user_account
(
    id              UUID PRIMARY KEY,
    organization_id UUID REFERENCES adm.organization (id),
    email           TEXT        NOT NULL,
    display_name    TEXT        NOT NULL,
    global_role     TEXT        NOT NULL CHECK (global_role IN ('SUPER_ADMIN', 'PARTNER', 'PROJECT_MANAGER',
                                                                'ANALYST', 'CLIENT', 'TARGET', 'OBSERVER')),
    locale          TEXT        NOT NULL DEFAULT 'id' CHECK (locale IN ('id', 'en')),
    status          TEXT        NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'DISABLED')),
    mfa_enabled     BOOLEAN     NOT NULL DEFAULT false,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX user_account_email_uq ON adm.user_account (lower(email));
CREATE INDEX user_account_organization_idx ON adm.user_account (organization_id);

CREATE TABLE adm.user_group
(
    id              UUID PRIMARY KEY,
    organization_id UUID REFERENCES adm.organization (id),
    name            TEXT        NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX user_group_name_uq ON adm.user_group (lower(name));

CREATE TABLE adm.group_member
(
    group_id UUID NOT NULL REFERENCES adm.user_group (id) ON DELETE CASCADE,
    user_id  UUID NOT NULL REFERENCES adm.user_account (id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, user_id)
);

CREATE TABLE adm.invitation
(
    id              UUID PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES adm.user_account (id),
    email           TEXT        NOT NULL,
    organization_id UUID REFERENCES adm.organization (id),
    role            TEXT        NOT NULL,
    invited_by      UUID,
    status          TEXT        NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    accepted_at     TIMESTAMPTZ
);
CREATE INDEX invitation_user_idx ON adm.invitation (user_id, status);

-- PRD ADM-05: notification preferences per user and notification type.
CREATE TABLE ntf.notification_preference
(
    user_id           UUID    NOT NULL,
    notification_type TEXT    NOT NULL,
    email_enabled     BOOLEAN NOT NULL DEFAULT true,
    in_app_enabled    BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (user_id, notification_type)
);
