package com.example.platform.security.abac;

import java.util.Map;

/**
 * Attribute-based access request: who (subject attributes), what (action), on which resource (resource attributes).
 */
public record AccessRequest(
        String subjectId,
        Map<String, Object> subjectAttributes,
        String action,
        String resourceType,
        Map<String, Object> resourceAttributes) {

    public AccessRequest {
        subjectAttributes = Map.copyOf(subjectAttributes);
        resourceAttributes = Map.copyOf(resourceAttributes);
    }
}
