package com.inpartnerhub.diligence.finding.internal;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.dataroom.DocumentDirectory;
import com.inpartnerhub.diligence.dataroom.DocumentDirectory.DocumentSummary;
import com.inpartnerhub.diligence.finding.FindingDirectory;
import com.inpartnerhub.diligence.finding.RiskMatrix;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

@Component
@Transactional(readOnly = true)
class FindingLookup implements FindingDirectory {

    /** Most severe first; unrated last. */
    private static final Comparator<Finding> BY_RISK = Comparator
            .comparing((Finding f) -> f.getRiskScore() == null ? 0 : f.getRiskScore()).reversed()
            .thenComparing(Finding::getNumber);

    private final FindingRepository findings;
    private final FindingDocumentRepository links;
    private final FindingAccess access;
    private final RiskMatrixService matrix;
    private final DocumentDirectory documents;

    FindingLookup(FindingRepository findings, FindingDocumentRepository links, FindingAccess access,
            RiskMatrixService matrix, DocumentDirectory documents) {
        this.findings = findings;
        this.links = links;
        this.access = access;
        this.matrix = matrix;
        this.documents = documents;
    }

    @Override
    public Optional<FindingSummary> find(UUID projectId, UUID findingId) {
        try {
            var finding = access.finding(access.project(projectId), findingId);
            return Optional.of(new FindingSummary(finding.getId(), finding.getNumber(), finding.getTitle(),
                    finding.getWorkstreamId(), finding.getStatus().name()));
        } catch (ResourceNotFoundException | AccessDeniedException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<ReleasedFinding> released(UUID projectId, UUID workstreamId) {
        var context = access.project(projectId);
        var released = findings.findByProjectIdOrderByNumber(projectId).stream()
                .filter(f -> f.getStatus() == FindingStatus.RELEASED)
                .filter(f -> workstreamId == null || workstreamId.equals(f.getWorkstreamId()))
                .filter(f -> !FindingAccess.isInternal(context) || context.coversWorkstream(f.getWorkstreamId()))
                .sorted(Comparator.comparing((Finding f) -> f.getWorkstreamId().toString()).thenComparing(BY_RISK))
                .toList();
        var byFinding = new HashMap<UUID, List<FindingDocument>>();
        released.forEach(f -> byFinding.put(f.getId(), links.findByFindingIdOrderByLinkedAt(f.getId())));
        var names = new HashMap<UUID, String>();
        documents.findAll(projectId, byFinding.values().stream().flatMap(List::stream)
                .map(FindingDocument::getDocumentId).distinct().toList())
                .forEach((DocumentSummary d) -> names.put(d.id(), d.name()));
        return released.stream().map(f -> new ReleasedFinding(f.getId(), f.getNumber(), f.getWorkstreamId(),
                f.getTitle(), f.getCategory(), f.getDescription(), f.getImpactDescription(), f.getMonetaryImpact(),
                f.getCurrency(), f.getRecommendation(), f.getImpact(), f.getLikelihood(), f.getRiskScore(),
                f.getRiskLevel(), byFinding.get(f.getId()).stream()
                        .filter(l -> names.containsKey(l.getDocumentId()))
                        .map(l -> new Source(names.get(l.getDocumentId()), l.getPageReference()))
                        .toList()))
                .toList();
    }

    @Override
    public RiskMatrix riskMatrix() {
        return matrix.current();
    }
}
