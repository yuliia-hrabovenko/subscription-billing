package com.subscriptionbilling.audit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Spring Data discovers this class by naming convention ({@code <RepositoryName>Impl})
 * and wires it in as the implementation for {@link AuditLogEntryRepositoryCustom}.
 */
class AuditLogEntryRepositoryImpl implements AuditLogEntryRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public AuditLogEntry append(AuditLogEntry entry) {
        entityManager.persist(entry);
        return entry;
    }
}
