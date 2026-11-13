package com.inpartnerhub.diligence.admin.internal;

import java.util.UUID;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import com.inpartnerhub.diligence.admin.AdminEvents.UserStatusChanged;
import com.inpartnerhub.diligence.security.AccountStatus;

/**
 * Answers {@link AccountStatus} from the account mirror (#173). Checked on every request, so the answer is cached in
 * the shared Redis cache and evicted once a status change is committed. An account the mirror does not know yet (first
 * request of a new SSO user) counts as enabled; Keycloak decides about those.
 */
@Service
class AccountStatusService implements AccountStatus {

    static final String CACHE = "account-disabled";

    private final UserAccountRepository users;
    private final CacheManager caches;

    AccountStatusService(UserAccountRepository users, CacheManager caches) {
        this.users = users;
        this.caches = caches;
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE, key = "#userId")
    public boolean isDisabled(UUID userId) {
        return users.findById(userId).map(u -> u.getStatus() == UserAccount.Status.DISABLED).orElse(false);
    }

    @TransactionalEventListener
    void on(UserStatusChanged event) {
        var cache = caches.getCache(CACHE);
        if (cache != null) {
            cache.evictIfPresent(event.userId());
        }
    }
}
