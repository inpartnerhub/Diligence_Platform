package com.inpartnerhub.diligence.admin.internal;

import java.util.Locale;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.admin.AdminEvents.UserLoggedIn;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.security.UserAuthenticated;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/**
 * Keeps the account mirror in sync with authenticated users: records logins, activates invited users and creates
 * accounts just in time for internal users signing in through SSO.
 */
@Service
class UserProvisioningService {

    private final UserAccountRepository users;
    private final InvitationRepository invitations;
    private final ApplicationEventPublisher events;

    UserProvisioningService(UserAccountRepository users, InvitationRepository invitations,
            ApplicationEventPublisher events) {
        this.users = users;
        this.invitations = invitations;
        this.events = events;
    }

    @EventListener
    @Transactional
    void onUserAuthenticated(UserAuthenticated event) {
        var user = event.user();
        var account = users.findById(user.id()).orElseGet(() -> users.save(UserAccount.provisioned(user.id(),
                user.email() == null ? user.username() : user.email().toLowerCase(Locale.ROOT),
                user.displayName() == null ? user.username() : user.displayName(),
                user.primaryRole().orElse(Role.OBSERVER))));
        account.recordLogin(event.authenticatedAt());
        invitations.findByUserIdAndStatus(user.id(), Invitation.Status.PENDING)
                .forEach(invitation -> invitation.accept(event.authenticatedAt()));
        events.publishEvent(new UserLoggedIn(user.id(), RequestMetadata.capture()));
    }
}
