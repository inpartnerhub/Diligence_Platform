package com.inpartnerhub.diligence.shared.web;

import java.net.URI;

/** Stable RFC 9457 problem type URIs. Clients may switch on these; never change an existing value. */
public final class ProblemTypes {

    public static final URI INTERNAL_ERROR = URI.create("urn:diligence:problem:internal-error");
    public static final URI NOT_FOUND = URI.create("urn:diligence:problem:not-found");
    public static final URI CONFLICT = URI.create("urn:diligence:problem:conflict");
    public static final URI FORBIDDEN = URI.create("urn:diligence:problem:forbidden");
    public static final URI UNAUTHORIZED = URI.create("urn:diligence:problem:unauthorized");
    public static final URI UPSTREAM_FAILURE = URI.create("urn:diligence:problem:upstream-failure");

    private ProblemTypes() {
    }
}
