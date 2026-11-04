-- PRD ADM-07 hardening: webhooks receive platform-wide events from every project, so only the Super Admin may
-- read or manage them (PRD Bab 3: Partners see only the projects under them). Deliveries run as the system role.
--
-- V19 compared app.role with 'admin', but the backend sets app.role to the realm role name
-- (Role.realmRoleName(), i.e. 'super_admin'), so those policies never matched the Super Admin.

DROP POLICY webhook_subscription_read ON adm.webhook_subscription;
DROP POLICY webhook_subscription_write ON adm.webhook_subscription;
DROP POLICY webhook_delivery_log_read ON adm.webhook_delivery_log;
DROP POLICY webhook_delivery_log_write ON adm.webhook_delivery_log;

CREATE POLICY webhook_subscription_read ON adm.webhook_subscription FOR SELECT USING (
    prj.is_system() OR prj.app_role() = 'super_admin');

CREATE POLICY webhook_subscription_write ON adm.webhook_subscription FOR ALL
    USING (prj.is_system() OR prj.app_role() = 'super_admin')
    WITH CHECK (prj.is_system() OR prj.app_role() = 'super_admin');

CREATE POLICY webhook_delivery_log_read ON adm.webhook_delivery_log FOR SELECT USING (
    prj.is_system() OR prj.app_role() = 'super_admin');

CREATE POLICY webhook_delivery_log_write ON adm.webhook_delivery_log FOR ALL
    USING (prj.is_system() OR prj.app_role() = 'super_admin')
    WITH CHECK (prj.is_system() OR prj.app_role() = 'super_admin');
