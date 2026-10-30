package com.inpartnerhub.diligence.shared.audit;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who did something and from where. Captured when an event is published, because audit listeners run later on
 * another thread where neither the request nor the security context is available.
 *
 * @param actorId   Keycloak subject of the acting user; {@code null} for system actions
 */
public record RequestMetadata(UUID actorId, String ipAddress, String userAgent) {

    public static final RequestMetadata SYSTEM = new RequestMetadata(null, null, "system");

    /** Captures the current request and principal; falls back to {@link #SYSTEM} outside a web request. */
    public static RequestMetadata capture() {
        var actor = Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .map(auth -> parseUuid(auth.getName()))
                .orElse(null);
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return actor == null ? SYSTEM : new RequestMetadata(actor, null, null);
        }
        var request = attributes.getRequest();
        // X-Forwarded-For is already resolved into getRemoteAddr() by forward-headers-strategy=framework.
        return new RequestMetadata(actor, request.getRemoteAddr(), truncate(request.getHeader("User-Agent")));
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 512 ? value : value.substring(0, 512);
    }
}
