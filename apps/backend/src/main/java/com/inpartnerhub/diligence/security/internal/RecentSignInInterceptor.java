package com.inpartnerhub.diligence.security.internal;

import java.time.Clock;
import java.time.Instant;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;

import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.RequiresRecentSignIn;
import com.inpartnerhub.diligence.shared.web.StepUpRequiredException;

/** Enforces {@link RequiresRecentSignIn} before the annotated method runs (#65). */
class RecentSignInInterceptor implements MethodInterceptor {

    private final ObjectProvider<CurrentUserProvider> users;
    private final ObjectProvider<StepUpProperties> properties;
    private final Clock clock;

    RecentSignInInterceptor(ObjectProvider<CurrentUserProvider> users, ObjectProvider<StepUpProperties> properties,
            Clock clock) {
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        var current = users.getObject();
        if (current.currentUser().isPresent()) {
            var maxAge = properties.getObject().maxAge();
            var signedIn = current.authenticatedAt();
            if (signedIn.isEmpty() || signedIn.get().plus(maxAge).isBefore(Instant.now(clock))) {
                var annotation = AnnotatedElementUtils.findMergedAnnotation(invocation.getMethod(),
                        RequiresRecentSignIn.class);
                var action = annotation == null ? "this action" : annotation.value();
                throw new StepUpRequiredException("Sign in again to confirm: " + action + ".", maxAge);
            }
        }
        return invocation.proceed();
    }
}
