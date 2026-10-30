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
 */
@ConfigurationProperties("diligence.identity")
record IdentityProperties(URI baseUrl, String realm, String adminClientId, String adminClientSecret,
        String webClientId, URI webRedirectUri, Duration invitationLifespan) {

    IdentityProperties {
        invitationLifespan = invitationLifespan == null ? Duration.ofHours(72) : invitationLifespan;
    }
}
