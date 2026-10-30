package com.inpartnerhub.diligence.admin.internal;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.admin.Directory;

@Service
@Transactional(readOnly = true)
class DirectoryService implements Directory {

    private final OrganizationRepository organizations;
    private final UserAccountRepository users;

    DirectoryService(OrganizationRepository organizations, UserAccountRepository users) {
        this.organizations = organizations;
        this.users = users;
    }

    @Override
    public Optional<OrganizationSummary> findOrganization(UUID id) {
        return organizations.findById(id).map(DirectoryService::summary);
    }

    @Override
    public Optional<UserSummary> findUser(UUID id) {
        return users.findById(id).map(DirectoryService::summary);
    }

    @Override
    public List<UserSummary> findUsers(List<UUID> ids) {
        return users.findAllById(ids).stream().map(DirectoryService::summary).toList();
    }

    List<OrganizationSummary> activeOrganizations(Organization.Type type) {
        return organizations.findAll(org.springframework.data.domain.Sort.by("name")).stream()
                .filter(o -> o.getStatus() == Organization.Status.ACTIVE)
                .filter(o -> type == null || o.getType() == type)
                .map(DirectoryService::summary)
                .toList();
    }

    Page<UserSummary> searchActiveUsers(UUID organizationId, String query, Pageable pageable) {
        Specification<UserAccount> spec = (root, q, cb) -> cb.notEqual(root.get("status"), UserAccount.Status.DISABLED);
        if (organizationId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("organizationId"), organizationId));
        }
        if (query != null && !query.isBlank()) {
            var pattern = "%" + query.strip().toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, q, cb) -> cb.or(cb.like(cb.lower(root.get("email")), pattern),
                    cb.like(cb.lower(root.get("displayName")), pattern)));
        }
        return users.findAll(spec, pageable).map(DirectoryService::summary);
    }

    static OrganizationSummary summary(Organization o) {
        return new OrganizationSummary(o.getId(), o.getName(), OrganizationType.valueOf(o.getType().name()),
                o.getStatus() == Organization.Status.ACTIVE);
    }

    static UserSummary summary(UserAccount u) {
        return new UserSummary(u.getId(), u.getEmail(), u.getDisplayName(), u.getOrganizationId(),
                u.getGlobalRole(), u.getStatus() != UserAccount.Status.DISABLED);
    }
}
