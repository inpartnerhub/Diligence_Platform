package com.inpartnerhub.diligence.project.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

import tools.jackson.databind.json.JsonMapper;

/** Project templates (PRD PRJ-03), managed by super admins and used by partners and project managers. */
@Service
@Transactional
class TemplateService {

    private final ProjectTemplateRepository templates;
    private final JsonMapper json;

    TemplateService(ProjectTemplateRepository templates, JsonMapper json) {
        this.templates = templates;
        this.json = json;
    }

    @Transactional(readOnly = true)
    List<ProjectTemplate> list() {
        return templates.findAll(Sort.by("name"));
    }

    @Transactional(readOnly = true)
    ProjectTemplate get(UUID id) {
        return templates.findById(id).orElseThrow(() -> new ResourceNotFoundException("Project template", id));
    }

    @Transactional(readOnly = true)
    TemplateDefinition definition(UUID id) {
        return parse(get(id).getDefinition());
    }

    ProjectTemplate create(String name, String description, TemplateDefinition definition) {
        var trimmed = name.strip();
        if (templates.existsByNameIgnoreCase(trimmed)) {
            throw new ResourceConflictException("A template named '" + trimmed + "' already exists");
        }
        definition.validate();
        return templates.save(new ProjectTemplate(trimmed, description, json.writeValueAsString(definition)));
    }

    ProjectTemplate update(UUID id, String name, String description, TemplateDefinition definition) {
        var template = get(id);
        var trimmed = name.strip();
        if (!template.getName().equalsIgnoreCase(trimmed) && templates.existsByNameIgnoreCase(trimmed)) {
            throw new ResourceConflictException("A template named '" + trimmed + "' already exists");
        }
        definition.validate();
        template.update(trimmed, description, json.writeValueAsString(definition));
        return template;
    }

    /** Copies a template under a new name so it can be adapted without changing the original (PRD PRJ-03). */
    ProjectTemplate duplicate(UUID id, String newName) {
        var source = get(id);
        return create(newName, source.getDescription(), parse(source.getDefinition()));
    }

    TemplateDefinition parse(String definition) {
        return json.readValue(definition, TemplateDefinition.class);
    }
}
