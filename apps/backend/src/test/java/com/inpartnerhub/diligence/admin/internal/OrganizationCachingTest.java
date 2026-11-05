package com.inpartnerhub.diligence.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.inpartnerhub.diligence.admin.AdminEvents.OrganizationUpdated;
import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/** Issue #60: organization summaries are read through the cache and evicted after a committed update. */
class OrganizationCachingTest {

    private static final OrganizationRepository ORGANIZATIONS = mock(OrganizationRepository.class);

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class Context {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }

        @Bean
        DirectoryService directoryService() {
            return new DirectoryService(ORGANIZATIONS, mock(UserAccountRepository.class));
        }

        @Bean
        OrganizationCacheEviction organizationCacheEviction(CacheManager caches) {
            return new OrganizationCacheEviction(caches);
        }
    }

    @Test
    void resolvesAnOrganizationOnceUntilItChanges() {
        var organization = new Organization("PT Target", Organization.Type.TARGET);
        when(ORGANIZATIONS.findById(organization.getId())).thenReturn(Optional.of(organization));
        try (var context = new AnnotationConfigApplicationContext(Context.class)) {
            var directory = context.getBean(Directory.class);

            assertThat(directory.findOrganization(organization.getId())).map(Directory.OrganizationSummary::name)
                    .contains("PT Target");
            assertThat(directory.findOrganization(organization.getId())).isPresent();
            verify(ORGANIZATIONS, times(1)).findById(organization.getId());

            organization.rename("PT Target Baru");
            context.getBean(OrganizationCacheEviction.class)
                    .on(new OrganizationUpdated(organization.getId(), "PT Target Baru", RequestMetadata.SYSTEM));
            assertThat(directory.findOrganization(organization.getId())).map(Directory.OrganizationSummary::name)
                    .contains("PT Target Baru");
            verify(ORGANIZATIONS, times(2)).findById(organization.getId());
        }
    }
}
