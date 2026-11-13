/**
 * Authentication (Keycloak-issued JWTs, PRD ADM-02), role model (PRD Bab 3) and authorization building blocks:
 * RBAC via {@link com.inpartnerhub.diligence.security.Role} and the PostgreSQL row-level security context per
 * transaction. Resource-level rules live in each module's access component ({@code ProjectAccess},
 * {@code DataRoomAccess}, ...), next to the data they protect.
 */
@ApplicationModule(displayName = "Security")
package com.inpartnerhub.diligence.security;

import org.springframework.modulith.ApplicationModule;
