package com.inpartnerhub.diligence.security;

import java.time.Instant;

/**
 * Published (throttled) when a user makes authenticated requests, so the admin module can provision accounts
 * just in time for SSO users and record the last login.
 */
public record UserAuthenticated(AuthenticatedUser user, Instant authenticatedAt) {
}
