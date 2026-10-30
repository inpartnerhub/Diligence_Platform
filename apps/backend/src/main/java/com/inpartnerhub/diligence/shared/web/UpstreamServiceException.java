package com.inpartnerhub.diligence.shared.web;

/** A dependent service (identity provider, storage, …) failed; mapped to 502 without leaking details. */
public class UpstreamServiceException extends RuntimeException {

    public UpstreamServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
