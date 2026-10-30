package com.inpartnerhub.diligence.security.internal;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param clientId        Keycloak client whose client roles are mapped to authorities
 * @param allowedOrigins  CORS origins permitted to call the API (the frontend and the gateway)
 */
@ConfigurationProperties("diligence.security")
record SecurityProperties(String clientId, List<String> allowedOrigins) {

    SecurityProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
