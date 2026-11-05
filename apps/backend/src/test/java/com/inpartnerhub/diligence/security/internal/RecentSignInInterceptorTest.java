package com.inpartnerhub.diligence.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;

import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.RequiresRecentSignIn;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.StepUpRequiredException;

/** Issue #65: sensitive actions need a sign-in with MFA within the step-up age; system work is not affected. */
class RecentSignInInterceptorTest {

    private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");

    static class Actions {

        @RequiresRecentSignIn("releasing a report to the client")
        String release() {
            return "released";
        }

        String read() {
            return "read";
        }
    }

    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final Actions actions = proxy();

    @SuppressWarnings("unchecked")
    private Actions proxy() {
        var userProvider = (ObjectProvider<CurrentUserProvider>) mock(ObjectProvider.class);
        when(userProvider.getObject()).thenReturn(users);
        var properties = (ObjectProvider<StepUpProperties>) mock(ObjectProvider.class);
        when(properties.getObject()).thenReturn(new StepUpProperties(Duration.ofMinutes(10)));
        var factory = new ProxyFactory(new Actions());
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new DefaultPointcutAdvisor(
                AnnotationMatchingPointcut.forMethodAnnotation(RequiresRecentSignIn.class),
                new RecentSignInInterceptor(userProvider, properties, Clock.fixed(NOW, ZoneOffset.UTC))));
        return (Actions) factory.getProxy();
    }

    private void signedIn(Instant authTime) {
        when(users.currentUser()).thenReturn(Optional.of(
                new AuthenticatedUser(UUID.randomUUID(), "p", "p@x.test", "P", Set.of(Role.PARTNER))));
        when(users.authenticatedAt()).thenReturn(Optional.ofNullable(authTime));
    }

    @Test
    void allowsARecentSignIn() {
        signedIn(NOW.minus(Duration.ofMinutes(9)));
        assertThat(actions.release()).isEqualTo("released");
    }

    @Test
    void refusesAnOldOrUnknownSignIn() {
        signedIn(NOW.minus(Duration.ofMinutes(11)));
        assertThatThrownBy(actions::release).isInstanceOf(StepUpRequiredException.class)
                .hasMessageContaining("releasing a report to the client")
                .satisfies(ex -> assertThat(((StepUpRequiredException) ex).maxAge()).hasMinutes(10));

        signedIn(null); // token without auth_time
        assertThatThrownBy(actions::release).isInstanceOf(StepUpRequiredException.class);
        assertThat(actions.read()).isEqualTo("read"); // other methods are not affected
    }

    @Test
    void letsSystemWorkThrough() {
        when(users.currentUser()).thenReturn(Optional.empty());
        assertThat(actions.release()).isEqualTo("released");
    }
}
