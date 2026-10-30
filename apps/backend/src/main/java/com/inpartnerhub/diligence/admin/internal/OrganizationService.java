package com.inpartnerhub.diligence.admin.internal;

import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.admin.AdminEvents.OrganizationCreated;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

@Service
@Transactional
class OrganizationService {

    private final OrganizationRepository organizations;
    private final ApplicationEventPublisher events;

    OrganizationService(OrganizationRepository organizations, ApplicationEventPublisher events) {
        this.organizations = organizations;
        this.events = events;
    }

    @Transactional(readOnly = true)
    Page<Organization> list(Pageable pageable) {
        return organizations.findAll(pageable);
    }

    Organization create(String name, Organization.Type type) {
        var trimmed = name.strip();
        if (organizations.existsByNameIgnoreCase(trimmed)) {
            throw new ResourceConflictException("An organization named '" + trimmed + "' already exists");
        }
        var organization = organizations.save(new Organization(trimmed, type));
        events.publishEvent(new OrganizationCreated(organization.getId(), organization.getName(), type.name(),
                RequestMetadata.capture()));
        return organization;
    }

    Organization update(UUID id, String name, Organization.Status status) {
        var organization = get(id);
        if (name != null && !name.isBlank()) {
            organization.rename(name.strip());
        }
        if (status != null) {
            organization.changeStatus(status);
        }
        return organization;
    }

    @Transactional(readOnly = true)
    Organization get(UUID id) {
        return organizations.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organization", id));
    }
}
