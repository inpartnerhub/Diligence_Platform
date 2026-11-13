package com.inpartnerhub.diligence.security.internal;

import java.io.IOException;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.inpartnerhub.diligence.security.AccountStatus;
import com.inpartnerhub.diligence.shared.web.ProblemTypes;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Refuses a disabled account at once (#173): disabling a user in Keycloak stops new sign-ins and refreshes, but an
 * access token already issued stays valid for up to its lifetime (5 min). Without an {@link AccountStatus} bean (web
 * slice tests) nothing is checked.
 */
class DisabledAccountFilter extends OncePerRequestFilter {

    private final ObjectProvider<AccountStatus> accounts;

    DisabledAccountFilter(ObjectProvider<AccountStatus> accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var status = accounts.getIfAvailable();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (status != null && authentication instanceof JwtAuthenticationToken token
                && status.isDisabled(UUID.fromString(token.getToken().getSubject()))) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"%s","title":"Forbidden","status":403,"detail":"Your account is disabled."}"""
                    .formatted(ProblemTypes.FORBIDDEN));
            return;
        }
        chain.doFilter(request, response);
    }
}
