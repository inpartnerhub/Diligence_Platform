package com.inpartnerhub.diligence.admin.internal;

import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.inpartnerhub.diligence.admin.AdminEvents.OrganizationUpdated;

/**
 * Drops a cached organization summary once a change is committed (issue #60), so no reader can re-cache the old
 * value between the eviction and the commit.
 */
@Component
class OrganizationCacheEviction {

    private final CacheManager caches;

    OrganizationCacheEviction(CacheManager caches) {
        this.caches = caches;
    }

    @TransactionalEventListener
    void on(OrganizationUpdated event) {
        var cache = caches.getCache(DirectoryService.ORGANIZATIONS_CACHE);
        if (cache != null) {
            cache.evictIfPresent(event.organizationId()); // immediate, unlike evict() which may be deferred
        }
    }
}
