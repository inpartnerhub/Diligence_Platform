package com.inpartnerhub.diligence.finding.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.finding.RiskMatrix;
import com.inpartnerhub.diligence.project.ProjectDirectory;
import com.inpartnerhub.diligence.project.WorkstreamType;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

@Service
@Transactional
class FindingLibraryService {

    private final FindingLibraryTemplateRepository templates;
    private final FindingService findingService;
    private final FindingAccess access;
    private final ProjectDirectory projects;

    FindingLibraryService(FindingLibraryTemplateRepository templates, FindingService findingService,
            FindingAccess access, ProjectDirectory projects) {
        this.templates = templates;
        this.findingService = findingService;
        this.access = access;
        this.projects = projects;
    }

    @Transactional(readOnly = true)
    List<FindingLibraryTemplate> list(WorkstreamType workstreamType, String q) {
        List<FindingLibraryTemplate> all;
        if (workstreamType != null) {
            all = templates.findByWorkstreamTypeOrderByCategoryAscTitleAsc(workstreamType);
        } else {
            all = templates.findAllByOrderByWorkstreamTypeAscCategoryAscTitleAsc();
        }

        if (q == null || q.isBlank()) {
            return all;
        }

        String queryLower = q.trim().toLowerCase();
        return all.stream()
                .filter(t -> t.getTitle().toLowerCase().contains(queryLower)
                        || t.getCategory().toLowerCase().contains(queryLower)
                        || t.getStandardDescription().toLowerCase().contains(queryLower)
                        || (t.getDefaultRecommendation() != null && t.getDefaultRecommendation().toLowerCase().contains(queryLower)))
                .toList();
    }

    @Transactional(readOnly = true)
    FindingLibraryTemplate get(UUID id) {
        return templates.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FindingLibraryTemplate", id));
    }

    FindingLibraryTemplate create(WorkstreamType workstreamType, String category, String title,
            String standardDescription, String defaultRecommendation, int defaultImpact, int defaultLikelihood) {
        validateInputs(workstreamType, category, title, standardDescription, defaultImpact, defaultLikelihood);
        var now = Instant.now();
        var template = new FindingLibraryTemplate(UUID.randomUUID(), workstreamType, category.trim(), title.trim(),
                standardDescription.trim(), defaultRecommendation != null ? defaultRecommendation.trim() : null,
                defaultImpact, defaultLikelihood, now, now);
        return templates.save(template);
    }

    FindingLibraryTemplate update(UUID id, WorkstreamType workstreamType, String category, String title,
            String standardDescription, String defaultRecommendation, int defaultImpact, int defaultLikelihood) {
        validateInputs(workstreamType, category, title, standardDescription, defaultImpact, defaultLikelihood);
        var template = get(id);
        template.update(workstreamType, category.trim(), title.trim(), standardDescription.trim(),
                defaultRecommendation != null ? defaultRecommendation.trim() : null, defaultImpact, defaultLikelihood,
                Instant.now());
        return templates.save(template);
    }

    void delete(UUID id) {
        var template = get(id);
        templates.delete(template);
    }

    Finding instantiate(UUID projectId, UUID templateId, UUID targetWorkstreamId) {
        var context = access.editableProject(projectId);
        var template = get(templateId);

        var projectWorkstreams = projects.workstreams(projectId);
        if (projectWorkstreams.isEmpty()) {
            throw new InvalidRequestException("Project has no active workstreams");
        }

        UUID workstreamId = targetWorkstreamId;
        if (workstreamId == null) {
            // Pick first workstream matching template type that analyst covers
            workstreamId = projectWorkstreams.stream()
                    .filter(w -> w.type() == template.getWorkstreamType() && context.coversWorkstream(w.id()))
                    .map(ProjectDirectory.WorkstreamSummary::id)
                    .findFirst()
                    .orElse(null);

            // Fallback to any workstream analyst covers
            if (workstreamId == null) {
                workstreamId = projectWorkstreams.stream()
                        .filter(w -> context.coversWorkstream(w.id()))
                        .map(ProjectDirectory.WorkstreamSummary::id)
                        .findFirst()
                        .orElseThrow(() -> new InvalidRequestException("You do not have access to any workstream in this project"));
            }
        } else {
            final UUID wId = workstreamId;
            var match = projectWorkstreams.stream().filter(w -> w.id().equals(wId)).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Workstream", wId));
            if (!context.coversWorkstream(match.id())) {
                throw new InvalidRequestException("Your role does not cover workstream " + match.type());
            }
        }

        var content = new Finding.Content(workstreamId, template.getTitle(), template.getCategory(),
                template.getStandardDescription(), null, null, null, template.getDefaultRecommendation());
        var rating = new FindingService.Rating(template.getDefaultImpact(), template.getDefaultLikelihood());

        return findingService.create(projectId, content, rating);
    }

    private void validateInputs(WorkstreamType workstreamType, String category, String title,
            String standardDescription, int impact, int likelihood) {
        if (workstreamType == null) {
            throw new InvalidRequestException("workstreamType is required");
        }
        if (category == null || category.isBlank() || category.length() > Finding.MAX_CATEGORY) {
            throw new InvalidRequestException("category must be between 1 and " + Finding.MAX_CATEGORY + " characters");
        }
        if (title == null || title.isBlank() || title.length() > Finding.MAX_TITLE) {
            throw new InvalidRequestException("title must be between 1 and " + Finding.MAX_TITLE + " characters");
        }
        if (standardDescription == null || standardDescription.isBlank() || standardDescription.length() > Finding.MAX_TEXT) {
            throw new InvalidRequestException("standardDescription must be between 1 and " + Finding.MAX_TEXT + " characters");
        }
        if (impact < RiskMatrix.MIN_SCALE || impact > RiskMatrix.MAX_SCALE) {
            throw new InvalidRequestException("defaultImpact must be between 1 and 5");
        }
        if (likelihood < RiskMatrix.MIN_SCALE || likelihood > RiskMatrix.MAX_SCALE) {
            throw new InvalidRequestException("defaultLikelihood must be between 1 and 5");
        }
    }
}
