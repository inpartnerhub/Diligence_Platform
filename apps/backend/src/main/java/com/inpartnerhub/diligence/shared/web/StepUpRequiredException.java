package com.inpartnerhub.diligence.shared.web;

import java.time.Duration;

/**
 * The action needs a recent sign-in (password + MFA) and the caller's last one is older than {@code maxAge}. Mapped to
 * 403 with {@link ProblemTypes#STEP_UP_REQUIRED}; the web app then signs the user in again and retries.
 */
public class StepUpRequiredException extends RuntimeException {

    private final transient Duration maxAge;

    public StepUpRequiredException(String message, Duration maxAge) {
        super(message);
        this.maxAge = maxAge;
    }

    public Duration maxAge() {
        return maxAge;
    }
}
