package com.inpartnerhub.diligence.finding.internal;

import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.inpartnerhub.diligence.finding.FindingEvents.RiskMatrixChanged;

/** Drops the cached risk matrix once a threshold change is committed (FND-03, issue #60). */
@Component
class RiskMatrixCacheEviction {

    private final CacheManager caches;

    RiskMatrixCacheEviction(CacheManager caches) {
        this.caches = caches;
    }

    @TransactionalEventListener
    void on(RiskMatrixChanged event) {
        var cache = caches.getCache(RiskMatrixService.CACHE);
        if (cache != null) {
            cache.clear();
        }
    }
}
