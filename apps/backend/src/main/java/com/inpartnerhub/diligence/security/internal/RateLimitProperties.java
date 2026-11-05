package com.inpartnerhub.diligence.security.internal;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-user API rate limits, in addition to Kong's per-IP limit (issue #63).
 *
 * @param enabled            switch for test and CI environments; production keeps it on
 * @param perMinute          requests one user may make per minute
 * @param expensivePerMinute requests per minute to the {@code expensivePaths} (search, downloads, exports)
 * @param expensivePaths     Ant-style path patterns counted against the stricter limit
 */
@ConfigurationProperties("diligence.rate-limit")
record RateLimitProperties(Boolean enabled, Integer perMinute, Integer expensivePerMinute,
        List<String> expensivePaths) {

    static final List<String> DEFAULT_EXPENSIVE_PATHS = List.of(
            "/api/**/search", "/api/**/search/**", "/api/**/download", "/api/**/export", "/api/**/export/**",
            "/api/**/export-pptx");

    RateLimitProperties {
        enabled = enabled == null || enabled;
        perMinute = perMinute == null || perMinute < 1 ? 600 : perMinute;
        expensivePerMinute = expensivePerMinute == null || expensivePerMinute < 1 ? 60 : expensivePerMinute;
        expensivePaths = expensivePaths == null || expensivePaths.isEmpty() ? DEFAULT_EXPENSIVE_PATHS
                : List.copyOf(expensivePaths);
    }
}
