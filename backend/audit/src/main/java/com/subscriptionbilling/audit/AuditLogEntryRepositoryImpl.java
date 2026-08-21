package com.subscriptionbilling.audit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Custom repository implementation for the append-only audit log.
 * Uses EntityManager.persist() rather than Spring Data save() to ensure
 * the repository has no update semantics.
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
