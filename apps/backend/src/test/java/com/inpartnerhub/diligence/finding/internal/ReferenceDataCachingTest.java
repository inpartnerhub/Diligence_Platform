package com.inpartnerhub.diligence.finding.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import com.inpartnerhub.diligence.admin.Directory.OrganizationSummary;
import com.inpartnerhub.diligence.admin.Directory.OrganizationType;
import com.inpartnerhub.diligence.finding.FindingEvents.RiskMatrixChanged;
import com.inpartnerhub.diligence.finding.RiskMatrix;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

/** Issue #60: the risk matrix is read through the cache, evicted after a change, and Redis-serialisable. */
class ReferenceDataCachingTest {

    private static final JdbcTemplate JDBC = mock(JdbcTemplate.class);

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class Context {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }

        @Bean
        RiskMatrixService riskMatrixService() {
            return new RiskMatrixService(JDBC, mock(CurrentUserProvider.class), mock(ApplicationEventPublisher.class));
        }

        @Bean
        RiskMatrixCacheEviction riskMatrixCacheEviction(CacheManager caches) {
            return new RiskMatrixCacheEviction(caches);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsTheRiskMatrixOnceUntilAChangeIsCommitted() {
        when(JDBC.query(anyString(), any(ResultSetExtractor.class))).thenReturn(new RiskMatrix(4, 8, 15));
        try (var context = new AnnotationConfigApplicationContext(Context.class)) {
            var service = context.getBean(RiskMatrixService.class);

            assertThat(service.current()).isEqualTo(new RiskMatrix(4, 8, 15));
            assertThat(service.current()).isEqualTo(new RiskMatrix(4, 8, 15));
            verify(JDBC, times(1)).query(anyString(), any(ResultSetExtractor.class));

            context.getBean(RiskMatrixCacheEviction.class)
                    .on(new RiskMatrixChanged(RiskMatrix.DEFAULT, 0, RequestMetadata.SYSTEM));
            service.current();
            verify(JDBC, times(2)).query(anyString(), any(ResultSetExtractor.class));
        }
    }

    @Test
    void cachedValuesSurviveJavaSerialisation() throws Exception {
        // The Redis cache stores values with JDK serialisation.
        var organization = new OrganizationSummary(UUID.randomUUID(), "PT Target", OrganizationType.TARGET, true, 730);
        assertThat(roundTrip(organization)).isEqualTo(organization);
        assertThat(roundTrip(new RiskMatrix(4, 8, 15))).isEqualTo(new RiskMatrix(4, 8, 15));
    }

    private static Object roundTrip(Object value) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return in.readObject();
        }
    }
}
