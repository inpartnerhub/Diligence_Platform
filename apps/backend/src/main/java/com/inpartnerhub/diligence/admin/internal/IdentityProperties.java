package com.inpartnerhub.diligence.admin.internal;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl             Keycloak base URL reachable from the backend (internal network address)
 * @param adminClientId       confidential client with a service account holding realm-management roles
 * @param adminClientSecret   injected from the environment / Vault, never committed
 * @param webClientId         client the invitation e-mail links back to after the required actions
 * @param webRedirectUri      where users land after completing the invitation actions
 * @param invitationLifespan  validity of the invitation e-mail link
 * @param connectTimeout      connect timeout of Admin API calls (#126)
 * @param readTimeout         read timeout of Admin API calls; they run inside a database transaction (#126)
 */
@ConfigurationProperties("diligence.identity")
record IdentityProperties(URI baseUrl, String realm, String adminClientId, String adminClientSecret,
        String webClientId, URI webRedirectUri, Duration invitationLifespan, Duration connectTimeout,
        Duration readTimeout) {

    IdentityProperties {
        invitationLifespan = invitationLifespan == null ? Duration.ofHours(72) : invitationLifespan;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
    }
}
