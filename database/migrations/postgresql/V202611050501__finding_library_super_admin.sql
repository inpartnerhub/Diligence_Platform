-- PRD FND-06: the finding library is a global template, managed by the Super Admin (PRD Bab 3); consultants read it.
-- V18 compared app.role with 'admin', but the backend sets app.role to the realm role name
-- (Role.realmRoleName(), i.e. 'super_admin'), so the policies never matched the Super Admin.

DROP POLICY finding_library_template_read ON fnd.finding_library_template;
DROP POLICY finding_library_template_write ON fnd.finding_library_template;

CREATE POLICY finding_library_template_read ON fnd.finding_library_template FOR SELECT USING (
    prj.is_system() OR prj.app_role() IN ('super_admin', 'partner', 'project_manager', 'analyst', 'observer'));

CREATE POLICY finding_library_template_write ON fnd.finding_library_template FOR ALL
    USING (prj.is_system() OR prj.app_role() = 'super_admin')
    WITH CHECK (prj.is_system() OR prj.app_role() = 'super_admin');
