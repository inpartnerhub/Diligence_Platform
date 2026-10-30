package com.example.platform.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** OpenAPI 3.x document metadata and the Keycloak OIDC security scheme used by Swagger UI. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    private static final String SCHEME = "keycloak";

    @Bean
    OpenAPI platformOpenApi(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        var flow = new OAuthFlow()
                .authorizationUrl(issuerUri + "/protocol/openid-connect/auth")
                .tokenUrl(issuerUri + "/protocol/openid-connect/token");
        return new OpenAPI()
                .info(new Info().title("Platform API").version("v1").description("Public REST API of the platform."))
                .components(new Components().addSecuritySchemes(SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .flows(new OAuthFlows().authorizationCode(flow))))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME));
    }
}
