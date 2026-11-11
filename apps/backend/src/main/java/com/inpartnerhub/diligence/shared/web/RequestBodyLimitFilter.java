package com.inpartnerhub.diligence.shared.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps request bodies that are not multipart uploads (#117). Kong and Spring allow 100 MB because uploads need it,
 * and the public OIDC client lets any signed-in user call the API directly, so without this a JSON body of up to
 * 100 MB would be read into memory before validation. Runs before authentication: a declared {@code Content-Length}
 * above the limit is refused at once with 413; a body without one (chunked) is counted while it is read and fails
 * as soon as it passes the limit. Multipart requests keep the upload limits ({@code spring.servlet.multipart.*}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestBodyLimitFilter(@Value("${diligence.http.max-body-size:1MB}") DataSize maxBody) {
        this.maxBytes = maxBody.toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > maxBytes) {
            tooLarge(response);
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    private static void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"" + ProblemTypes.VALIDATION + "\",\"title\":\"Content Too Large\","
                + "\"status\":413,\"detail\":\"The request body is too large.\"}");
    }

    /** Thrown while reading a body without Content-Length once it passes the limit; mapped to 413. */
    public static final class BodyTooLargeException extends IOException {

        BodyTooLargeException() {
            super("The request body is too large.");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }
    }

    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        LimitedStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws BodyTooLargeException {
            read += n;
            if (read > maxBytes) {
                throw new BodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
