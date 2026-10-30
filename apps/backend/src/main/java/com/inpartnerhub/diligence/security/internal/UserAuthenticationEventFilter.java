package com.inpartnerhub.diligence.security.internal;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.inpartnerhub.diligence.security.UserAuthenticated;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Publishes {@link UserAuthenticated} at most once per user per {@link #THROTTLE} per instance, keeping account
 * provisioning off the hot path of every request.
 */
class UserAuthenticationEventFilter extends OncePerRequestFilter {

    static final Duration THROTTLE = Duration.ofMinutes(15);

    private final ApplicationEventPublisher events;
    private final Map<UUID, Instant> lastPublished = new ConcurrentHashMap<>();

    UserAuthenticationEventFilter(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
            var user = JwtCurrentUserProvider.toUser(token);
            var now = Instant.now();
            var previous = lastPublished.get(user.id());
            if (previous == null || previous.plus(THROTTLE).isBefore(now)) {
                lastPublished.put(user.id(), now);
                events.publishEvent(new UserAuthenticated(user, now));
            }
        }
        chain.doFilter(request, response);
    }
}
