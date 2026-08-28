package com.subscriptionbilling.webhooks.dedupe;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uses {@code EntityManager} directly rather than delegating to another
 * already-transactional method: {@link #insert} must let a constraint violation
 * propagate straight out of its own {@code REQUIRES_NEW} boundary, not be caught by a
 * nested call and leave this transaction marked rollback-only.
 */
class WebhookEventRepositoryImpl implements WebhookEventRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(WebhookEvent event) {
        entityManager.persist(event);
        entityManager.flush();
    }
}
