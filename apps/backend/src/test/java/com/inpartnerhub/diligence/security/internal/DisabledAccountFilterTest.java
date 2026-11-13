package com.inpartnerhub.diligence.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.inpartnerhub.diligence.security.AccountStatus;
import com.inpartnerhub.diligence.security.Role;

/** #173: a disabled account is refused even while its access token is still valid. */
class DisabledAccountFilterTest {

    private final UUID user = UUID.randomUUID();
    private final AccountStatus status = mock(AccountStatus.class);

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void refusesADisabledAccount() throws Exception {
        signIn();
        when(status.isDisabled(user)).thenReturn(true);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter(status).doFilter(new MockHttpServletRequest("GET", "/api/v1/projects"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("disabled");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void passesAnEnabledAccount() throws Exception {
        signIn();
        var chain = new MockFilterChain();

        filter(status).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void checksNothingWithoutAnAccountStatus() throws Exception {
        signIn();
        var chain = new MockFilterChain();

        filter(null).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private static DisabledAccountFilter filter(AccountStatus status) {
        var provider = (ObjectProvider<AccountStatus>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(status);
        return new DisabledAccountFilter(provider);
    }

    private void signIn() {
        var jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority(Role.ANALYST.authority())), jwt.getSubject()));
    }
}
