package com.inpartnerhub.diligence.admin.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inpartnerhub.diligence.admin.AdminEvents.UserInvited;
import com.inpartnerhub.diligence.admin.AdminEvents.UserRoleChanged;
import com.inpartnerhub.diligence.admin.AdminEvents.UserStatusChanged;
import com.inpartnerhub.diligence.admin.IdentityAdminClient;
import com.inpartnerhub.diligence.admin.IdentityAdminClient.NewIdentityUser;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

/** User lifecycle (PRD ADM-01): invite, change role, enable/disable. Keycloak is updated first, then the mirror. */
@Service
@Transactional
class UserAdministrationService {

    private static final Logger log = LoggerFactory.getLogger(UserAdministrationService.class);

    record InviteCommand(String email, String firstName, String lastName, UUID organizationId, Role role,
            String locale) {
    }

    record UserFilter(UUID organizationId, Role role, UserAccount.Status status, String query) {
    }

    private final UserAccountRepository users;
    private final InvitationRepository invitations;
    private final OrganizationService organizations;
    private final IdentityAdminClient identity;
    private final IdentityProperties properties;
    private final ApplicationEventPublisher events;

    UserAdministrationService(UserAccountRepository users, InvitationRepository invitations,
            OrganizationService organizations, IdentityAdminClient identity, IdentityProperties properties,
            ApplicationEventPublisher events) {
        this.users = users;
        this.invitations = invitations;
        this.organizations = organizations;
        this.identity = identity;
        this.properties = properties;
        this.events = events;
    }

    @Transactional(readOnly = true)
    Page<UserAccount> list(UserFilter filter, Pageable pageable) {
        return users.findAll(specification(filter), pageable);
    }

    @Transactional(readOnly = true)
    UserAccount get(UUID id) {
        return users.findById(id).orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    UserAccount invite(InviteCommand command) {
        var email = command.email().strip().toLowerCase(java.util.Locale.ROOT);
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ResourceConflictException("A user with e-mail " + email + " already exists");
        }
        if (command.organizationId() != null) {
            organizations.get(command.organizationId()); // must exist
        }
        var metadata = RequestMetadata.capture();
        var userId = identity.createUser(new NewIdentityUser(email, command.firstName(), command.lastName(),
                command.locale(), command.organizationId()));
        try {
            identity.replaceRole(userId, command.role());
            identity.sendRequiredActionsEmail(userId, requiredActions(command.role()));
            var displayName = (command.firstName() + " " + command.lastName()).strip();
            var account = users.save(UserAccount.invited(userId, command.organizationId(), email, displayName,
                    command.role(), command.locale()));
            invitations.save(new Invitation(userId, email, command.organizationId(), command.role(),
                    metadata.actorId(), properties.invitationLifespan()));
            events.publishEvent(new UserInvited(userId, command.organizationId(), command.role(), metadata));
            return account;
        } catch (RuntimeException ex) {
            log.warn("Invitation of {} failed; removing identity {}", email, userId);
            identity.deleteUser(userId);
            throw ex;
        }
    }

    UserAccount changeRole(UUID id, Role role) {
        var account = get(id);
        var previous = account.getGlobalRole();
        if (previous == role) {
            return account;
        }
        identity.replaceRole(id, role);
        account.changeRole(role);
        events.publishEvent(new UserRoleChanged(id, previous, role, RequestMetadata.capture()));
        return account;
    }

    UserAccount setEnabled(UUID id, boolean enabled) {
        var account = get(id);
        identity.setEnabled(id, enabled);
        account.setEnabled(enabled);
        events.publishEvent(new UserStatusChanged(id, enabled, RequestMetadata.capture()));
        return account;
    }

    /** Keycloak required actions for a new user; NDA and OTP depend on the role (PRD 10.1, 10.2). */
    static List<String> requiredActions(Role role) {
        var actions = new ArrayList<>(List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"));
        if (role.isExternal()) {
            actions.add("TERMS_AND_CONDITIONS");
        }
        if (role.requiresMfa()) {
            actions.add("CONFIGURE_TOTP");
        }
        return List.copyOf(actions);
    }

    private static Specification<UserAccount> specification(UserFilter filter) {
        Specification<UserAccount> spec = (root, query, cb) -> cb.conjunction();
        if (filter.organizationId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("organizationId"), filter.organizationId()));
        }
        if (filter.role() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("globalRole"), filter.role()));
        }
        if (filter.status() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), filter.status()));
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            var pattern = "%" + filter.query().strip().toLowerCase(java.util.Locale.ROOT) + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")), pattern),
                    cb.like(cb.lower(root.get("displayName")), pattern)));
        }
        return spec;
    }
}
