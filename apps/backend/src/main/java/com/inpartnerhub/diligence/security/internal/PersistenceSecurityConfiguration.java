package com.inpartnerhub.diligence.security.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.inpartnerhub.diligence.security.CurrentUserProvider;

import jakarta.persistence.EntityManagerFactory;

/** Replaces Spring Boot's default JPA transaction manager with the RLS-aware one. */
@Configuration(proxyBeanMethods = false)
class PersistenceSecurityConfiguration {

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory emf, CurrentUserProvider currentUserProvider) {
        return new RowLevelSecurityTransactionManager(emf, currentUserProvider);
    }
}
