package com.inpartnerhub.diligence.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.inpartnerhub.diligence.security.UserAuthenticated;

class UserAuthenticationEventFilterTest {

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final UserAuthenticationEventFilter filter = new UserAuthenticationEventFilter(events);

    @BeforeEach
    void signIn() {
        var jwt = Jwt.withTokenValue("t").header("alg", "none").subject(UUID.randomUUID().toString())
                .claim("preferred_username", "pm").claim("email", "pm@example.com")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of(), jwt.getSubject()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aFailedLoginRecordDoesNotFailTheRequest() throws Exception {
        doThrow(new IllegalStateException("duplicate e-mail")).when(events).publishEvent(any(UserAuthenticated.class));
        var chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void recordsEachUserOncePerThrottleWindow() throws Exception {
        for (var i = 0; i < 3; i++) {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());
        }

        verify(events, times(1)).publishEvent(any(UserAuthenticated.class));
    }
}
