package com.inpartnerhub.diligence.project.internal;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

/**
 * Template content (PRD PRJ-03): workstreams, request-list items (used by the request module from sprint 9–10),
 * finding categories per workstream and the data-room folder structure (sprint 5–6).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record TemplateDefinition(List<WorkstreamType> workstreams, List<RequestItem> requestItems,
        List<FindingCategory> findingCategories, List<String> folders) {

    TemplateDefinition {
        workstreams = workstreams == null ? List.of() : List.copyOf(workstreams);
        requestItems = requestItems == null ? List.of() : List.copyOf(requestItems);
        findingCategories = findingCategories == null ? List.of() : List.copyOf(findingCategories);
        folders = folders == null ? List.of() : List.copyOf(folders);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RequestItem(WorkstreamType workstream, String category, String description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FindingCategory(WorkstreamType workstream, String name) {
    }

    /** Rejects definitions that would create inconsistent projects. */
    void validate() {
        if (workstreams.isEmpty()) {
            throw new InvalidRequestException("A template needs at least one workstream");
        }
        if (workstreams.stream().distinct().count() != workstreams.size()) {
            throw new InvalidRequestException("Workstreams must not repeat");
        }
        for (var item : requestItems) {
            if (item.workstream() == null || !workstreams.contains(item.workstream())) {
                throw new InvalidRequestException("Request item '" + item.description()
                        + "' refers to a workstream that is not part of the template");
            }
            if (item.description() == null || item.description().isBlank()) {
                throw new InvalidRequestException("Request items need a description");
            }
        }
        for (var category : findingCategories) {
            if (category.workstream() == null || !workstreams.contains(category.workstream())) {
                throw new InvalidRequestException("Finding category '" + category.name()
                        + "' refers to a workstream that is not part of the template");
            }
        }
    }
}
