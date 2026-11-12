package com.inpartnerhub.diligence.shared.web;

import java.io.IOException;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Bounds the multipart uploads one instance accepts at the same time (#142). Tomcat writes every uploaded file (up to
 * 100 MB) to {@code /tmp} until the request ends, and {@code /tmp} is a size-limited volume in Kubernetes: unbounded
 * parallel uploads (a dropped folder, several users at once) could fill it and get the pod evicted, failing every
 * request on it. Over the limit an upload is refused with 503 and {@code Retry-After} before its body is read; the
 * web app retries it. Runs after authentication, so anonymous requests never hold a slot.
 */
@Component
@Order(UploadConcurrencyFilter.ORDER)
public class UploadConcurrencyFilter extends OncePerRequestFilter {

    /** Just after Spring Security's filter chain (registered at order -100 by default). */
    static final int ORDER = -100 + 10;

    static final int RETRY_AFTER_SECONDS = 5;

    private final Semaphore slots;

    public UploadConcurrencyFilter(@Value("${diligence.http.max-concurrent-uploads:8}") int maxConcurrentUploads) {
        this.slots = new Semaphore(Math.max(maxConcurrentUploads, 1));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/")) {
            chain.doFilter(request, response);
            return;
        }
        if (!slots.tryAcquire()) {
            busy(response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            slots.release();
        }
    }

    int available() {
        return slots.availablePermits();
    }

    private static void busy(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"" + ProblemTypes.RATE_LIMITED + "\",\"title\":\"Service Unavailable\","
                + "\"status\":503,\"detail\":\"Too many uploads at the moment. The upload is retried shortly.\"}");
    }
}
