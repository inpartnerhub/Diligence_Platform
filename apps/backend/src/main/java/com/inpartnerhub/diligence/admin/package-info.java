/**
 * Administration (PRD ADM-01): organizations, user accounts, groups and invitations of external users.
 * Identity itself (credentials, MFA, SSO) lives in Keycloak; this module keeps the application-side account and
 * drives Keycloak through {@link com.inpartnerhub.diligence.admin.IdentityAdminClient}.
 */
@ApplicationModule(displayName = "Administration", allowedDependencies = { "shared", "security" })
package com.inpartnerhub.diligence.admin;

import org.springframework.modulith.ApplicationModule;
