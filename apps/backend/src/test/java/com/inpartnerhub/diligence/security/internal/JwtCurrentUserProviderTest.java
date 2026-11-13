package com.inpartnerhub.diligence.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** #160: step-up counts only a sign-in whose token shows the second factor ({@code amr}). */
class JwtCurrentUserProviderTest {

    private static final Instant AUTH_TIME = Instant.parse("2026-11-13T01:00:00Z");

    @Test
    void aSignInWithOtpCounts() {
        assertThat(JwtCurrentUserProvider.signInWithMfa(token(List.of("pwd", "otp")), "otp")).contains(AUTH_TIME);
    }

    @Test
    void aPasswordOnlySignInDoesNotCount() {
        assertThat(JwtCurrentUserProvider.signInWithMfa(token(List.of("pwd")), "otp")).isEmpty();
    }

    @Test
    void aTokenWithoutAmrDoesNotCount() {
        assertThat(JwtCurrentUserProvider.signInWithMfa(token(null), "otp")).isEmpty();
    }

    @Test
    void theMfaMethodDefaultsToOtp() {
        assertThat(new StepUpProperties(null, " ").mfaMethod()).isEqualTo("otp");
    }

    private static Jwt token(List<String> amr) {
        var builder = Jwt.withTokenValue("t").header("alg", "none").subject("u").claim("auth_time", AUTH_TIME);
        if (amr != null) {
            builder.claim("amr", amr);
        }
        return builder.build();
    }
}
