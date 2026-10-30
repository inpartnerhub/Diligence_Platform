package com.inpartnerhub.diligence.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.inpartnerhub.diligence.admin.AdminEvents.UserLoggedIn;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.security.UserAuthenticated;

class UserProvisioningServiceTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final InvitationRepository invitations = mock(InvitationRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final UserProvisioningService service = new UserProvisioningService(users, invitations, events);

    @Test
    void activatesInvitedUserOnFirstLogin() {
        var id = UUID.randomUUID();
        var account = UserAccount.invited(id, null, "t@x.test", "T", Role.TARGET, "id");
        var invitation = new Invitation(id, "t@x.test", null, Role.TARGET, null, Duration.ofHours(72));
        when(users.findById(id)).thenReturn(Optional.of(account));
        when(invitations.findByUserIdAndStatus(id, Invitation.Status.PENDING)).thenReturn(List.of(invitation));
        var at = Instant.now();

        service.onUserAuthenticated(new UserAuthenticated(user(id, Set.of(Role.TARGET)), at));

        assertThat(account.getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
        assertThat(account.getLastLoginAt()).isEqualTo(at);
        assertThat(invitation.getStatus()).isEqualTo(Invitation.Status.ACCEPTED);
        verify(events).publishEvent(any(UserLoggedIn.class));
    }

    @Test
    void provisionsSsoUserJustInTime() {
        var id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onUserAuthenticated(new UserAuthenticated(user(id, Set.of()), Instant.now()));

        var saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getGlobalRole()).isEqualTo(Role.OBSERVER); // least privilege until assigned
        assertThat(saved.getValue().getEmail()).isEqualTo("sso.user@example.com");
        assertThat(saved.getValue().getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
    }

    private static AuthenticatedUser user(UUID id, Set<Role> roles) {
        return new AuthenticatedUser(id, "sso.user", "SSO.User@example.com", "SSO User", roles);
    }
}
