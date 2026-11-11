package com.inpartnerhub.diligence.finding.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.dataroom.AnnotationFindings;
import com.inpartnerhub.diligence.finding.FindingDirectory;

/** Answers the data room's question whether an annotation may reference a finding (#91). */
@Component
class FindingAnnotationReferences implements AnnotationFindings {

    private final FindingDirectory findings;

    FindingAnnotationReferences(FindingDirectory findings) {
        this.findings = findings;
    }

    @Override
    public boolean visible(UUID projectId, UUID findingId) {
        return findings.find(projectId, findingId).isPresent();
    }
}
