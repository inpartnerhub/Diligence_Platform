package com.inpartnerhub.diligence.security.internal;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.inpartnerhub.diligence.shared.web.ProblemTypes;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limits requests per authenticated user (issue #63): Kong limits per IP, which a user (or a stolen session) can
 * spread over many addresses. Counts are kept in Redis per user and one-minute window, shared by every replica;
 * search, downloads and exports have a stricter limit. Over the limit the request gets 429 with {@code Retry-After}
 * and is counted as {@code diligence_ratelimit_rejected_total}. When Redis is unavailable the filter lets requests
 * through (the per-IP limit still applies) and skips Redis for a minute.
 */
class UserRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UserRateLimitFilter.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration REDIS_BACKOFF = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final Clock clock;
    private final AntPathMatcher paths = new AntPathMatcher();
    private final Counter rejectedGeneral;
    private final Counter rejectedExpensive;
    private volatile Instant redisRetryAt = Instant.MIN;

    UserRateLimitFilter(StringRedisTemplate redis, RateLimitProperties properties, MeterRegistry meters,
            Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        this.rejectedGeneral = rejected(meters, "general");
        this.rejectedExpensive = rejected(meters, "expensive");
    }

    private static Counter rejected(MeterRegistry meters, String tier) {
        return Counter.builder("diligence.ratelimit.rejected")
                .description("Requests refused by the per-user rate limit (#63)")
                .tag("tier", tier)
                .register(meters);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var user = userId();
        var now = Instant.now(clock);
        if (user == null || !properties.enabled() || now.isBefore(redisRetryAt)) {
            chain.doFilter(request, response);
            return;
        }
        var expensive = isExpensive(request.getRequestURI());
        var window = now.getEpochSecond() / WINDOW.toSeconds();
        var retryAfter = WINDOW.toSeconds() - now.getEpochSecond() % WINDOW.toSeconds();
        try {
            if (exceeded("all", user, window, properties.perMinute())
                    || expensive && exceeded("expensive", user, window, properties.expensivePerMinute())) {
                (expensive ? rejectedExpensive : rejectedGeneral).increment();
                reject(response, retryAfter);
                return;
            }
        } catch (RuntimeException ex) {
            redisRetryAt = now.plus(REDIS_BACKOFF);
            log.warn("Per-user rate limiting unavailable for {}: {}", REDIS_BACKOFF, ex.getMessage());
        }
        chain.doFilter(request, response);
    }

    boolean isExpensive(String uri) {
        return properties.expensivePaths().stream().anyMatch(pattern -> paths.match(pattern, uri));
    }

    private boolean exceeded(String tier, UUID user, long window, int limit) {
        var key = "diligence:ratelimit:%s:%s:%d".formatted(tier, user, window);
        var count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, WINDOW.multipliedBy(2));
        }
        return count != null && count > limit;
    }

    private static UUID userId() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
            try {
                return UUID.fromString(token.getToken().getSubject());
            } catch (IllegalArgumentException | NullPointerException ex) {
                return null;
            }
        }
        return null;
    }

    private static void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"%s","title":"Too Many Requests","status":429,\
                "detail":"Too many requests. Try again in %d seconds."}"""
                .formatted(ProblemTypes.RATE_LIMITED, retryAfterSeconds));
    }
}
