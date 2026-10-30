package com.inpartnerhub.diligence.admin.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.security.internal.SecurityConfiguration;

/** Admin API is restricted to SUPER_ADMIN at the API layer, not just hidden in the UI (PRD 8.1, 10.1). */
@WebMvcTest(controllers = { UserAdminController.class, OrganizationController.class },
        properties = { "diligence.security.client-id=diligence-api",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test" })
@Import(SecurityConfiguration.class)
class AdminEndpointSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    UserAdministrationService users;

    @MockitoBean
    OrganizationService organizations;

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminRolesAreForbidden() throws Exception {
        for (var role : List.of(Role.PARTNER, Role.PROJECT_MANAGER, Role.ANALYST, Role.CLIENT, Role.TARGET,
                Role.OBSERVER)) {
            mvc.perform(get("/api/v1/admin/users").with(as(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("urn:diligence:problem:forbidden"));
            mvc.perform(get("/api/v1/admin/organizations").with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void superAdminCanListUsers() throws Exception {
        when(users.list(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/v1/admin/users").with(as(Role.SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void invitationIsValidated() throws Exception {
        mvc.perform(post("/api/v1/admin/users/invitations").with(as(Role.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"firstName\":\"\",\"role\":\"TARGET\",\"locale\":\"fr\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void superAdminCanInvite() throws Exception {
        var invited = UserAccount.invited(UUID.randomUUID(), null, "t@x.test", "T U", Role.TARGET, "id");
        when(users.invite(any())).thenReturn(invited);

        mvc.perform(post("/api/v1/admin/users/invitations").with(as(Role.SUPER_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"t@x.test","firstName":"T","lastName":"U","role":"TARGET","locale":"id"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INVITED"))
                .andExpect(jsonPath("$.mfaEnabled").value(true));
    }

    private static RequestPostProcessor as(Role role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority(role.authority()));
    }
}
