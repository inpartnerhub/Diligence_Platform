package com.inpartnerhub.diligence.dataroom;

import java.util.UUID;

/**
 * Lets the data room check the finding an annotation points at (PRD DOC-08, #91). The findings module depends on
 * the data room, so it implements this interface instead of the data room calling it.
 */
public interface AnnotationFindings {

    /** True when the finding exists in the project and the caller may see it (row-level security). */
    boolean visible(UUID projectId, UUID findingId);
}
