package com.inpartnerhub.diligence.admin.internal;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** Sends one webhook request. The receiver's body is never read back: it could expose an internal service (SSRF). */
@FunctionalInterface
interface WebhookTransport {

    /** @return the HTTP status code of the response */
    int post(URI target, Map<String, String> headers, String jsonBody) throws IOException;
}
