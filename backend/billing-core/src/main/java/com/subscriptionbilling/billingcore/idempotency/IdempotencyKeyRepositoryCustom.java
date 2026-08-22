package com.subscriptionbilling.billingcore.idempotency;

import java.util.UUID;

/**
 * The writes {@link IdempotencyKeyRepository} needs beyond what {@link
 * org.springframework.data.jpa.repository.JpaRepository} already provides — both always
 * run in their own isolated, immediately-committed transaction regardless of whatever
 * transaction the caller already has open (e.g. {@code SubscriptionService.cancel}).
 */
public interface IdempotencyKeyRepositoryCustom {

    /**
     * Propagates a {@link org.springframework.dao.DataIntegrityViolationException} on a
     * unique-constraint race rather than catching it — must let the failure cross its
     * own transactional boundary uncaught, so its {@code REQUIRES_NEW} transaction rolls
     * back cleanly instead of being caught-internally-but-left-marked-rollback-only.
     *
     * @param record the key record to insert
     */
    void insert(IdempotencyKeyRecord record);

    /**
     * A no-op if already gone (e.g. a concurrent caller deleted the same record first).
     *
     * @param id the {@link IdempotencyKeyRecord}'s id
     */
    void deleteIsolated(UUID id);

    /**
     * Same as {@link #deleteIsolated(UUID)}, keyed by the natural key instead of the id.
     *
     * @param customerId     the Customer the key was recorded against
     * @param operation      the operation name the key was recorded against
     * @param idempotencyKey the key to delete
     */
    void deleteIsolated(UUID customerId, String operation, String idempotencyKey);
}
