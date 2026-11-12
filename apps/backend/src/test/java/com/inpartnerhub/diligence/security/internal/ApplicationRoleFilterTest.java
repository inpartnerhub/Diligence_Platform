package com.inpartnerhub.diligence.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.inpartnerhub.diligence.security.Role;

/** #152: tokens without any application role are refused before any controller or transaction runs. */
class ApplicationRoleFilterTest {

    private final ApplicationRoleFilter filter = new ApplicationRoleFilter();

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void refusesATokenWithoutApplicationRole() throws Exception {
        signIn(List.of(new SimpleGrantedAuthority("ROLE_OFFLINE_ACCESS")));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/projects"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("urn:diligence:problem:forbidden");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void passesATokenWithAnApplicationRole() throws Exception {
        signIn(List.of(new SimpleGrantedAuthority(Role.OBSERVER.authority())));
        var chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/projects"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void leavesUnauthenticatedRequestsToTheAuthorizationRules() throws Exception {
        var chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private static void signIn(List<GrantedAuthority> authorities) {
        var jwt = Jwt.withTokenValue("t").header("alg", "none").subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, authorities, jwt.getSubject()));
    }
}
