package com.inpartnerhub.diligence.admin.internal;

import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.admin.AdminEvents.OrganizationCreated;
import com.inpartnerhub.diligence.admin.AdminEvents.OrganizationUpdated;
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
        return create(name, type, 365);
    }

    Organization create(String name, Organization.Type type, Integer defaultRetentionDays) {
        var trimmed = name.strip();
        if (organizations.existsByNameIgnoreCase(trimmed)) {
            throw new ResourceConflictException("An organization named '" + trimmed + "' already exists");
        }
        int retention = defaultRetentionDays != null && defaultRetentionDays > 0 ? defaultRetentionDays : 365;
        var organization = organizations.save(new Organization(trimmed, type, retention));
        events.publishEvent(new OrganizationCreated(organization.getId(), organization.getName(), type.name(),
                RequestMetadata.capture()));
        return organization;
    }

    Organization update(UUID id, String name, Organization.Status status) {
        return update(id, name, status, null);
    }

    Organization update(UUID id, String name, Organization.Status status, Integer defaultRetentionDays) {
        var organization = get(id);
        if (name != null && !name.isBlank()) {
            organization.rename(name.strip());
        }
        if (status != null) {
            organization.changeStatus(status);
        }
        if (defaultRetentionDays != null && defaultRetentionDays > 0) {
            organization.updateRetentionDays(defaultRetentionDays);
        }
        events.publishEvent(new OrganizationUpdated(organization.getId(), organization.getName(), RequestMetadata.capture()));
        return organization;
    }

    @Transactional(readOnly = true)
    Organization get(UUID id) {
        return organizations.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organization", id));
    }
}
