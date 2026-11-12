package com.inpartnerhub.diligence.project.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.admin.AdminEvents.OrganizationDeactivated;
import com.inpartnerhub.diligence.project.ProjectEvents.ProjectMemberAccessRevoked;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/**
 * Deactivating an organization ends the project access of its people (#156): every active client membership of its
 * client projects and target membership of its target projects expires now. The memberships stay (who had access
 * remains visible) and each revocation is audited. Reactivating the organization does not restore access; members are
 * added again deliberately. Runs after the commit without a signed-in user, i.e. as the RLS system role.
 */
@Component
class OrganizationAccessListener {

    static final String REASON = "organization-deactivated";

    private static final Logger log = LoggerFactory.getLogger(OrganizationAccessListener.class);

    private final ProjectMemberRepository members;
    private final ApplicationEventPublisher events;

    OrganizationAccessListener(ProjectMemberRepository members, ApplicationEventPublisher events) {
        this.members = members;
        this.events = events;
    }

    @ApplicationModuleListener
    void on(OrganizationDeactivated event) {
        var affected = members.findActiveExternalMembers(event.organizationId(), ProjectRole.CLIENT,
                ProjectRole.TARGET);
        if (affected.isEmpty()) {
            return;
        }
        members.expireMembers(affected.stream().map(ProjectMember::getId).toList());
        affected.forEach(m -> events.publishEvent(new ProjectMemberAccessRevoked(m.getProjectId(), m.getId(),
                m.getUserId(), REASON, RequestMetadata.SYSTEM)));
        log.info("Ended {} project memberships of deactivated organization {}", affected.size(),
                event.organizationId());
    }
}
