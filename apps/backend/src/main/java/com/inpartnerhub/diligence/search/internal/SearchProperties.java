package com.inpartnerhub.diligence.search.internal;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param username  internal-user-database account when fine-grained access control is on; empty locally
 * @param password  injected from Vault ({@code OPENSEARCH_PASSWORD}), never committed
 */
@ConfigurationProperties("diligence.search")
record SearchProperties(URI url, String indexPrefix, String username, String password) {

    SearchProperties {
        indexPrefix = indexPrefix == null || indexPrefix.isBlank() ? "diligence" : indexPrefix;
    }

    boolean hasCredentials() {
        return username != null && !username.isBlank();
    }

    String documentsIndex() {
        return indexPrefix + "-documents";
    }
}
