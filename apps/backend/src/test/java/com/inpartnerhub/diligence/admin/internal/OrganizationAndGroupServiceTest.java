package com.inpartnerhub.diligence.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.inpartnerhub.diligence.admin.AdminEvents.OrganizationCreated;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

class OrganizationAndGroupServiceTest {

    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final OrganizationService organizations = new OrganizationService(organizationRepository, events);

    private final UserGroupRepository groupRepository = mock(UserGroupRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final GroupService groups = new GroupService(groupRepository, users);

    @Test
    void createsOrganizationAndPublishesEvent() {
        when(organizationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var created = organizations.create("  PT Target Tbk ", Organization.Type.TARGET);

        assertThat(created.getName()).isEqualTo("PT Target Tbk");
        assertThat(created.getStatus()).isEqualTo(Organization.Status.ACTIVE);
        verify(events).publishEvent(any(OrganizationCreated.class));
    }

    @Test
    void rejectsDuplicateOrganizationName() {
        when(organizationRepository.existsByNameIgnoreCase("Acme")).thenReturn(true);

        assertThatThrownBy(() -> organizations.create("Acme", Organization.Type.CLIENT))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void updatesNameAndStatus() {
        var organization = new Organization("Old", Organization.Type.CLIENT);
        when(organizationRepository.findById(organization.getId())).thenReturn(Optional.of(organization));

        organizations.update(organization.getId(), "New", Organization.Status.INACTIVE);
        organizations.update(organization.getId(), " ", null);

        assertThat(organization.getName()).isEqualTo("New");
        assertThat(organization.getStatus()).isEqualTo(Organization.Status.INACTIVE);
    }

    @Test
    void listsAndFailsOnUnknownOrganization() {
        when(organizationRepository.findAll(PageRequest.of(0, 5))).thenReturn(new PageImpl<>(List.of()));
        assertThat(organizations.list(PageRequest.of(0, 5))).isEmpty();

        var id = UUID.randomUUID();
        when(organizationRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> organizations.get(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createsGroupAndReplacesMembers() {
        when(groupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var group = groups.create(" Legal team ", "Legal workstream reviewers", null);
        var member = UUID.randomUUID();
        when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        when(users.existsById(member)).thenReturn(true);

        groups.replaceMembers(group.getId(), Set.of(member));

        assertThat(group.getName()).isEqualTo("Legal team");
        assertThat(group.getMemberIds()).containsExactly(member);
    }

    @Test
    void groupValidation() {
        when(groupRepository.existsByNameIgnoreCase("Dup")).thenReturn(true);
        assertThatThrownBy(() -> groups.create("Dup", null, null)).isInstanceOf(ResourceConflictException.class);

        var group = new UserGroup("G", null, null);
        when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        assertThatThrownBy(() -> groups.replaceMembers(group.getId(), Set.of(UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);

        when(groupRepository.findAll(Sort.by("name"))).thenReturn(List.of(group));
        assertThat(groups.list()).containsExactly(group);
    }
}
