package com.inpartnerhub.diligence.request;

import java.util.Optional;
import java.util.UUID;

/** Request items visible to the caller, for modules that refer to them (e.g. Q&A, PRD QNA-01). */
public interface RequestItemDirectory {

    /** Empty when the item does not exist in the project or the caller may not see it. */
    Optional<ItemSummary> find(UUID projectId, UUID itemId);

    record ItemSummary(UUID id, String number, String description, UUID workstreamId) {
    }
}
