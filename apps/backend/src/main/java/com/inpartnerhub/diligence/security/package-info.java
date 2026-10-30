/**
 * Authentication (Keycloak-issued JWTs, PRD ADM-02), role model (PRD Bab 3) and authorization building blocks:
 * RBAC via {@link com.inpartnerhub.diligence.security.Role}, ABAC via the {@code abac} named interface, and
 * PostgreSQL row-level security context per transaction.
 */
@ApplicationModule(displayName = "Security")
package com.inpartnerhub.diligence.security;

import org.springframework.modulith.ApplicationModule;
