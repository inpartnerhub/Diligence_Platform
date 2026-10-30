package com.inpartnerhub.diligence.security.internal;

import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.inpartnerhub.diligence.security.CurrentUserProvider;

import jakarta.persistence.EntityManagerFactory;

/**
 * Starts every transaction with the PostgreSQL row-level-security context (ADR-0004):
 * <ul>
 * <li>{@code SET LOCAL ROLE diligence_app}: a non-owner, non-superuser role, so RLS policies always apply even when
 * the pool logs in as the schema owner;</li>
 * <li>{@code app.user_id} / {@code app.role}: read by the policies in {@code V5__row_level_security.sql}.</li>
 * </ul>
 * Without an authenticated user (message listeners, schedulers) the role is {@code system}, which policies treat as
 * trusted internal processing. Both settings are transaction-local and vanish on commit/rollback.
 */
class RowLevelSecurityTransactionManager extends JpaTransactionManager {

    static final String DATABASE_ROLE = "diligence_app";
    static final String SYSTEM_ROLE = "system";

    private final transient CurrentUserProvider currentUserProvider;

    RowLevelSecurityTransactionManager(EntityManagerFactory emf, CurrentUserProvider currentUserProvider) {
        super(emf);
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        var holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        var entityManager = holder.getEntityManager();
        var user = currentUserProvider.currentUser();
        var userId = user.map(u -> u.id().toString()).orElse("");
        var role = user.flatMap(u -> u.primaryRole()).map(r -> r.realmRoleName()).orElse(SYSTEM_ROLE);

        entityManager.createNativeQuery("SET LOCAL ROLE " + DATABASE_ROLE).executeUpdate();
        entityManager.createNativeQuery("SELECT set_config('app.user_id', :userId, true), set_config('app.role', :role, true)")
                .setParameter("userId", userId)
                .setParameter("role", role)
                .getSingleResult();
    }
}
