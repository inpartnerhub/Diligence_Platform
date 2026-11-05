package com.inpartnerhub.diligence.finding.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.inpartnerhub.diligence.admin.Directory.OrganizationSummary;
import com.inpartnerhub.diligence.admin.Directory.OrganizationType;
import com.inpartnerhub.diligence.finding.RiskMatrix;

/**
 * Issue #60: the cached reference values round-trip through a real Redis with the cache manager's default
 * (JDK) serialisation, including the "no such organization" null entry. {@code Cache.put} may be applied
 * asynchronously, so reads after a put wait for it; evictions use the immediate variants like the listeners do.
 */
@Tag("integration")
@Testcontainers
class RedisReferenceCacheIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test
    void storesAndReadsReferenceValues() throws InterruptedException {
        var connections = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connections.afterPropertiesSet();
        connections.start();
        try {
            var caches = RedisCacheManager.builder(connections)
                    .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig(getClass().getClassLoader()))
                    .build();
            caches.afterPropertiesSet();

            var matrix = caches.getCache(RiskMatrixService.CACHE);
            matrix.put("current", new RiskMatrix(4, 8, 15));
            assertThat(await(matrix, "current").get()).isEqualTo(new RiskMatrix(4, 8, 15));

            var organizations = caches.getCache("organizations");
            var id = UUID.randomUUID();
            var summary = new OrganizationSummary(id, "PT Klien", OrganizationType.CLIENT, true, 365);
            organizations.put(id, summary);
            assertThat(await(organizations, id).get()).isEqualTo(summary);

            var missing = UUID.randomUUID();
            organizations.put(missing, Optional.empty().orElse(null));
            assertThat(await(organizations, missing).get()).isNull(); // cached "not found"

            assertThat(organizations.evictIfPresent(id)).isTrue();
            assertThat(organizations.get(id)).isNull();
            matrix.invalidate();
            assertThat(matrix.get("current")).isNull();
        } finally {
            connections.destroy();
        }
    }

    private static Cache.ValueWrapper await(Cache cache, Object key) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        var value = cache.get(key);
        while (value == null && System.nanoTime() < deadline) {
            Thread.sleep(20);
            value = cache.get(key);
        }
        assertThat(value).as("cache entry %s", key).isNotNull();
        return value;
    }
}
