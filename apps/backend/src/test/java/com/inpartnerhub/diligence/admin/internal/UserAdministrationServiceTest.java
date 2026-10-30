package com.inpartnerhub.diligence.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import com.inpartnerhub.diligence.admin.AdminEvents.UserInvited;
import com.inpartnerhub.diligence.admin.AdminEvents.UserRoleChanged;
import com.inpartnerhub.diligence.admin.AdminEvents.UserStatusChanged;
import com.inpartnerhub.diligence.admin.IdentityAdminClient;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;
import com.inpartnerhub.diligence.shared.web.UpstreamServiceException;

class UserAdministrationServiceTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final InvitationRepository invitations = mock(InvitationRepository.class);
    private final OrganizationService organizations = mock(OrganizationService.class);
    private final IdentityAdminClient identity = mock(IdentityAdminClient.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final IdentityProperties properties = new IdentityProperties(null, "diligence", "admin", "secret",
            "diligence-web", null, Duration.ofHours(72));
    private final UserAdministrationService service = new UserAdministrationService(users, invitations, organizations,
            identity, properties, events);

    private final UUID organizationId = UUID.randomUUID();
    private final UUID keycloakId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invitations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(identity.createUser(any())).thenReturn(keycloakId);
    }

    @Test
    void invitesExternalUserWithNdaAndOtp() {
        var account = service.invite(command(" Target.User@Example.com ", Role.TARGET));

        assertThat(account.getId()).isEqualTo(keycloakId);
        assertThat(account.getEmail()).isEqualTo("target.user@example.com");
        assertThat(account.getStatus()).isEqualTo(UserAccount.Status.INVITED);
        assertThat(account.isMfaEnabled()).isTrue();
        verify(identity).replaceRole(keycloakId, Role.TARGET);
        verify(identity).sendRequiredActionsEmail(keycloakId,
                List.of("VERIFY_EMAIL", "UPDATE_PASSWORD", "TERMS_AND_CONDITIONS", "CONFIGURE_TOTP"));
        var invitation = ArgumentCaptor.forClass(Invitation.class);
        verify(invitations).save(invitation.capture());
        assertThat(invitation.getValue().getStatus()).isEqualTo(Invitation.Status.PENDING);
        verify(events).publishEvent(any(UserInvited.class));
    }

    @Test
    void internalUsersGetNoNdaOrOtpAction() {
        assertThat(UserAdministrationService.requiredActions(Role.ANALYST))
                .containsExactly("VERIFY_EMAIL", "UPDATE_PASSWORD");
        assertThat(UserAdministrationService.requiredActions(Role.SUPER_ADMIN))
                .containsExactly("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP");
    }

    @Test
    void rejectsDuplicateEmail() {
        when(users.existsByEmailIgnoreCase("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.invite(command("dup@example.com", Role.CLIENT)))
                .isInstanceOf(ResourceConflictException.class);
        verify(identity, never()).createUser(any());
    }

    @Test
    void removesIdentityWhenInvitationFails() {
        doThrow(new UpstreamServiceException("smtp down", null)).when(identity)
                .sendRequiredActionsEmail(eq(keycloakId), anyList());

        assertThatThrownBy(() -> service.invite(command("x@example.com", Role.CLIENT)))
                .isInstanceOf(UpstreamServiceException.class);
        verify(identity).deleteUser(keycloakId);
        verify(users, never()).save(any());
    }

    @Test
    void changesRoleInKeycloakAndMirror() {
        var account = UserAccount.invited(keycloakId, organizationId, "a@x.test", "A", Role.ANALYST, "id");
        when(users.findById(keycloakId)).thenReturn(Optional.of(account));

        service.changeRole(keycloakId, Role.PROJECT_MANAGER);

        assertThat(account.getGlobalRole()).isEqualTo(Role.PROJECT_MANAGER);
        verify(identity).replaceRole(keycloakId, Role.PROJECT_MANAGER);
        verify(events).publishEvent(any(UserRoleChanged.class));
    }

    @Test
    void unchangedRoleIsANoOp() {
        var account = UserAccount.invited(keycloakId, organizationId, "a@x.test", "A", Role.ANALYST, "id");
        when(users.findById(keycloakId)).thenReturn(Optional.of(account));

        service.changeRole(keycloakId, Role.ANALYST);

        verify(identity, never()).replaceRole(any(), any());
    }

    @Test
    void disablesAndEnablesUser() {
        var account = UserAccount.invited(keycloakId, organizationId, "a@x.test", "A", Role.CLIENT, "en");
        when(users.findById(keycloakId)).thenReturn(Optional.of(account));

        service.setEnabled(keycloakId, false);
        assertThat(account.getStatus()).isEqualTo(UserAccount.Status.DISABLED);

        service.setEnabled(keycloakId, true);
        assertThat(account.getStatus()).isEqualTo(UserAccount.Status.INVITED);
        verify(identity).setEnabled(keycloakId, false);
        verify(identity).setEnabled(keycloakId, true);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(any(UserStatusChanged.class));
    }

    @Test
    void unknownUserIsNotFound() {
        when(users.findById(keycloakId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(keycloakId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsWithFilters() {
        Page<UserAccount> page = new PageImpl<>(List.of());
        when(users.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        var result = service.list(new UserAdministrationService.UserFilter(organizationId, Role.CLIENT,
                UserAccount.Status.ACTIVE, "acme"), PageRequest.of(0, 20));

        assertThat(result).isSameAs(page);
    }

    private UserAdministrationService.InviteCommand command(String email, Role role) {
        return new UserAdministrationService.InviteCommand(email, "First", "Last", organizationId, role, "id");
    }
}
