package com.inpartnerhub.diligence.security.abac;

/**
 * A single ABAC rule. Modules contribute policies as beans; the {@link AccessPolicyEvaluator} combines them.
 */
public interface AccessPolicy {

    /** Whether this policy has an opinion on the request. */
    boolean supports(AccessRequest request);

    /** Decision for a supported request. */
    boolean permits(AccessRequest request);
}
