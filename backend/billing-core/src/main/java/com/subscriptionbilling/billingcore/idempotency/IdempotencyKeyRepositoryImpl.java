package com.subscriptionbilling.billingcore.idempotency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Uses {@code EntityManager} directly rather than delegating to another already-
 * transactional repository method: {@link #insert} in particular must let a constraint
 * violation propagate straight out of its own {@code REQUIRES_NEW} boundary, not be
 * caught by a nested call and leave this transaction marked rollback-only.
 */
class IdempotencyKeyRepositoryImpl implements IdempotencyKeyRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(IdempotencyKeyRecord record) {
        entityManager.persist(record);
        entityManager.flush();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteIsolated(UUID id) {
        entityManager.createQuery("DELETE FROM IdempotencyKeyRecord r WHERE r.id = :id")
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteIsolated(UUID customerId, String operation, String idempotencyKey) {
        entityManager.createQuery(
                        "DELETE FROM IdempotencyKeyRecord r WHERE r.customerId = :customerId "
                                + "AND r.operation = :operation AND r.idempotencyKey = :idempotencyKey")
                .setParameter("customerId", customerId)
                .setParameter("operation", operation)
                .setParameter("idempotencyKey", idempotencyKey)
                .executeUpdate();
    }
}
