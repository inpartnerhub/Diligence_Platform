package com.inpartnerhub.diligence.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Issue #63: per-user rate limit with a stricter tier for search, downloads and exports. */
class UserRateLimitFilterTest {

    // 30 s into a minute window: Retry-After is the rest of the window.
    private static final Instant NOW = Instant.parse("2026-11-06T03:00:30Z");
    private static final UUID USER = UUID.randomUUID();

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final Map<String, Long> counts = new HashMap<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UserRateLimitFilter filter = new UserRateLimitFilter(redis,
            new RateLimitProperties(true, 3, 1, null), meters, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenAnswer(inv -> counts.merge(inv.getArgument(0), 1L, Long::sum));
        var jwt = Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse call(String uri) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", uri), response, new MockFilterChain());
        return response;
    }

    @Test
    void limitsEachUserPerMinuteAndAnswers429WithRetryAfter() throws Exception {
        for (var i = 0; i < 3; i++) {
            assertThat(call("/api/v1/projects").getStatus()).isEqualTo(200);
        }
        var refused = call("/api/v1/projects");

        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("30");
        assertThat(refused.getContentType()).isEqualTo("application/problem+json");
        assertThat(refused.getContentAsString()).contains("urn:diligence:problem:rate-limited");
        assertThat(meters.counter("diligence.ratelimit.rejected", "tier", "general").count()).isEqualTo(1);
        // The key expires after the window; it is set once, on the first request of the window.
        verify(redis, times(1)).expire(anyString(), org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(2)));
    }

    @Test
    void searchDownloadsAndExportsHaveTheStricterLimit() throws Exception {
        assertThat(call("/api/v1/projects/p/search").getStatus()).isEqualTo(200);
        assertThat(call("/api/v1/projects/p/documents/d/download").getStatus()).isEqualTo(429);
        assertThat(meters.counter("diligence.ratelimit.rejected", "tier", "expensive").count()).isEqualTo(1);
        assertThat(call("/api/v1/projects/p/findings").getStatus()).isEqualTo(200); // general budget left

        assertThat(filter.isExpensive("/api/v1/projects/p/requests/export")).isTrue();
        assertThat(filter.isExpensive("/api/v1/admin/audit-logs/export")).isTrue();
        assertThat(filter.isExpensive("/api/v1/projects/p/reports/r/export-pptx")).isTrue();
        assertThat(filter.isExpensive("/api/v1/projects/p/folders/f/download")).isTrue();
        assertThat(filter.isExpensive("/api/v1/projects/p/documents/d/versions/v/pages/3")).isFalse();
    }

    @Test
    void skipsAnonymousCallersAndFailsOpenWhenRedisIsDown() throws Exception {
        SecurityContextHolder.clearContext();
        assertThat(call("/api/v1/projects").getStatus()).isEqualTo(200);
        verify(values, never()).increment(anyString());

        setUp();
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(call("/api/v1/projects").getStatus()).isEqualTo(200);
        assertThat(call("/api/v1/projects").getStatus()).isEqualTo(200); // back-off: Redis is not tried again
        verify(values, times(1)).increment(anyString());
    }

    @Test
    void canBeSwitchedOff() throws Exception {
        var off = new UserRateLimitFilter(redis, new RateLimitProperties(false, 1, 1, null), meters,
                Clock.fixed(NOW, ZoneOffset.UTC));
        for (var i = 0; i < 5; i++) {
            var response = new MockHttpServletResponse();
            off.doFilter(new MockHttpServletRequest("GET", "/api/v1/projects"), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        verify(values, never()).increment(anyString());
    }
}
