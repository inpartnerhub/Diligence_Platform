package com.inpartnerhub.diligence.shared.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Turns on Spring's cache abstraction; the store is Redis ({@code spring.cache.type: redis}, TTL
 * {@code spring.cache.redis.time-to-live}), shared by every replica so an eviction reaches all of them. Only
 * reference data that is identical for every caller and not protected by row-level security is cached (issue #60);
 * cached values must be {@link java.io.Serializable}.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CachingConfiguration {
}
