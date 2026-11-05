package com.inpartnerhub.diligence.finding.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * (JDK) serialisation, including the "no such organization" null entry.
 */
@Tag("integration")
@Testcontainers
class RedisReferenceCacheIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test
    void storesAndReadsReferenceValues() {
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
            assertThat(matrix.get("current", RiskMatrix.class)).isEqualTo(new RiskMatrix(4, 8, 15));

            var organizations = caches.getCache("organizations");
            var id = UUID.randomUUID();
            var summary = new OrganizationSummary(id, "PT Klien", OrganizationType.CLIENT, true, 365);
            organizations.put(id, summary);
            assertThat(organizations.get(id, OrganizationSummary.class)).isEqualTo(summary);

            var missing = UUID.randomUUID();
            organizations.put(missing, Optional.empty().orElse(null));
            assertThat(organizations.get(missing)).isNotNull(); // cached "not found"
            assertThat(organizations.get(missing).get()).isNull();

            organizations.evict(id);
            assertThat(organizations.get(id)).isNull();
        } finally {
            connections.destroy();
        }
    }
}
