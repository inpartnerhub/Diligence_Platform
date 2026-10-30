package com.example.platform.security.internal;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.platform.shared.web.ApiPaths;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Smoke-test endpoint for the end-to-end OIDC flow (frontend → Kong → backend). */
@RestController
@RequestMapping(ApiPaths.V1 + "/me")
@Tag(name = "Identity")
class CurrentUserController {

    record CurrentUser(String subject, String username, String email, List<String> roles) {
    }

    @GetMapping
    @Operation(summary = "Return the authenticated principal as seen by the backend")
    CurrentUser me(@AuthenticationPrincipal Jwt jwt, JwtAuthenticationToken authentication) {
        return new CurrentUser(
                jwt.getSubject(),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"),
                KeycloakJwtAuthenticationConverter.rolesOf(authentication));
    }
}
