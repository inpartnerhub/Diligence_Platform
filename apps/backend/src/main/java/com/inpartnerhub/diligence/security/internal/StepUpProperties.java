package com.inpartnerhub.diligence.security.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxAge    how recent a sign-in with MFA must be for {@code @RequiresRecentSignIn} actions (#65)
 * @param mfaMethod the token's {@code amr} value that proves the second factor (#160): Keycloak's OTP form reports
 *                  {@code otp} (realm: authenticator config {@code default.reference.value})
 */
@ConfigurationProperties("diligence.step-up")
record StepUpProperties(Duration maxAge, String mfaMethod) {

    static final String DEFAULT_MFA_METHOD = "otp";

    StepUpProperties {
        maxAge = maxAge == null || maxAge.isNegative() || maxAge.isZero() ? Duration.ofMinutes(10) : maxAge;
        mfaMethod = mfaMethod == null || mfaMethod.isBlank() ? DEFAULT_MFA_METHOD : mfaMethod;
    }
}
