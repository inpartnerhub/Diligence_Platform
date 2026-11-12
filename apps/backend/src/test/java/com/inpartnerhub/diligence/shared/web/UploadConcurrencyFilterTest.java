package com.inpartnerhub.diligence.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

/** #142: at most N multipart uploads per instance; others get 503 with Retry-After before their body is read. */
class UploadConcurrencyFilterTest {

    @Test
    void refusesUploadsBeyondTheLimitAndFreesTheSlotAfterwards() throws Exception {
        var filter = new UploadConcurrencyFilter(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicReference<MockHttpServletResponse>();

        var running = Thread.ofVirtual().start(() -> {
            var response = new MockHttpServletResponse();
            try {
                filter.doFilter(upload(), response, (request, res) -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            first.set(response);
        });
        entered.await();

        var refused = new MockHttpServletResponse();
        FilterChain mustNotRun = (request, response) -> {
            throw new AssertionError("the body of a refused upload must not be read");
        };
        filter.doFilter(upload(), refused, mustNotRun);
        assertThat(refused.getStatus()).isEqualTo(503);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("5");
        assertThat(refused.getContentAsString()).contains("urn:diligence:problem:rate-limited");

        release.countDown();
        running.join();
        assertThat(first.get().getStatus()).isEqualTo(200);
        assertThat(filter.available()).isEqualTo(1);

        var next = new MockHttpServletResponse();
        filter.doFilter(upload(), next, (request, response) -> { });
        assertThat(next.getStatus()).isEqualTo(200);
    }

    @Test
    void otherRequestsAreNotCounted() throws Exception {
        var filter = new UploadConcurrencyFilter(1);
        var json = new MockHttpServletRequest("POST", "/api/v1/projects");
        json.setContentType("application/json");
        filter.doFilter(json, new MockHttpServletResponse(), (request, response) -> {
            var nested = new MockHttpServletResponse();
            filter.doFilter(upload(), nested, (r, s) -> { });
            assertThat(nested.getStatus()).isEqualTo(200);
        });
        assertThat(filter.available()).isEqualTo(1);
    }

    @Test
    void aFailingUploadReleasesItsSlot() throws Exception {
        var filter = new UploadConcurrencyFilter(1);
        try {
            filter.doFilter(upload(), new MockHttpServletResponse(), (request, response) -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // the upload failed
        }
        assertThat(filter.available()).isEqualTo(1);
    }

    private static MockHttpServletRequest upload() {
        var request = new MockHttpServletRequest("POST", "/api/v1/projects/p/folders/f/documents");
        request.setContentType("multipart/form-data; boundary=x");
        return request;
    }
}
