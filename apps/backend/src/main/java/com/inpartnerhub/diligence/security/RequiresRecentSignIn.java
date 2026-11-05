package com.inpartnerhub.diligence.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Step-up authentication for sensitive actions (PRD ADM-02, issue #65): a signed-in caller must have signed in with
 * password + MFA within {@code diligence.step-up.max-age} (token {@code auth_time}); otherwise the call fails with
 * {@link com.inpartnerhub.diligence.shared.web.StepUpRequiredException} (403, the web app signs the user in again).
 * System work without a user (scheduled jobs, listeners) is not affected. Put it on Spring bean methods.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresRecentSignIn {

    /** What the user is confirming, shown in the problem detail. */
    String value();
}
