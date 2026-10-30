package com.example.platform.security.abac;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Deny-by-default evaluator: access is granted only if at least one policy supports the request and every
 * supporting policy permits it.
 */
@Component
public class AccessPolicyEvaluator {

    private final List<AccessPolicy> policies;

    public AccessPolicyEvaluator(List<AccessPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    public boolean isPermitted(AccessRequest request) {
        var applicable = policies.stream().filter(p -> p.supports(request)).toList();
        return !applicable.isEmpty() && applicable.stream().allMatch(p -> p.permits(request));
    }
}
