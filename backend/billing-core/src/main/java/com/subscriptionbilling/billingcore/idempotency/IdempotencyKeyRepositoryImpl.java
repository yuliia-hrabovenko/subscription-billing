package com.subscriptionbilling.billingcore.idempotency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Custom repository implementation backing {@link IdempotencyKeyRepositoryCustom}.
 * Uses {@code EntityManager} directly rather than delegating to another already-
 * transactional repository method: {@link #insert} in particular must let a
 * constraint violation propagate straight out of its own {@code REQUIRES_NEW}
 * transaction's boundary and roll back normally, rather than being caught by a nested
 * call and leaving this transaction marked rollback-only for a method further up the
 * stack to stumble into.
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
}
