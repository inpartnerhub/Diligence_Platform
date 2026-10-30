package com.inpartnerhub.diligence.ingestion;

/**
 * A pipeline step failed. {@code transientFailure} distinguishes an unreachable service (worth retrying) from a
 * permanent refusal (e.g. the scanner's size limit), which fails the document immediately.
 */
public class ScanException extends RuntimeException {

    private final boolean transientFailure;

    public ScanException(String message, boolean transientFailure, Throwable cause) {
        super(message, cause);
        this.transientFailure = transientFailure;
    }

    public boolean isTransientFailure() {
        return transientFailure;
    }
}
