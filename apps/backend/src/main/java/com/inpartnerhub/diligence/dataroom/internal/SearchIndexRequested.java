package com.inpartnerhub.diligence.dataroom.internal;

import java.util.UUID;

/**
 * Internal: the current version of a document changed state, so its search entry must be refreshed. Handled after
 * commit by {@link SearchIndexer}; the publication is persisted, so an OpenSearch outage only delays indexing.
 */
public record SearchIndexRequested(UUID documentId, UUID versionId) {
}
