package com.inpartnerhub.diligence.security.internal;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.inpartnerhub.diligence.shared.web.ProblemTypes;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Deny by default at the identity level (#152): a valid token that carries none of the application roles (for example
 * an account imported by an SSO broker before an administrator assigned a role) is refused with 403 on every
 * endpoint. Runs after {@link UserAuthenticationEventFilter}, so the login is still recorded and the account appears
 * in the user administration, where a Super Admin can assign its role.
 */
class ApplicationRoleFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApplicationRoleFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                && JwtCurrentUserProvider.toUser(token).roles().isEmpty()) {
            log.warn("Refused a request: account {} has no application role", token.getName());
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"%s","title":"Forbidden","status":403,\
                    "detail":"Your account has no role in the platform yet. Ask an administrator to assign one."}"""
                    .formatted(ProblemTypes.FORBIDDEN));
            return;
        }
        chain.doFilter(request, response);
    }
}
