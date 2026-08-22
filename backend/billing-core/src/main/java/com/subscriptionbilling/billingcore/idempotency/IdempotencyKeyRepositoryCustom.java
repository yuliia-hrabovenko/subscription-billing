package com.subscriptionbilling.billingcore.idempotency;

import java.util.UUID;

/**
 * The writes {@link IdempotencyKeyRepository} needs beyond what {@link
 * org.springframework.data.jpa.repository.JpaRepository} already provides, both always
 * running in their own isolated, immediately-committed transaction regardless of
 * whatever transaction the caller already has open (e.g. a state-changing use case like
 * {@code SubscriptionService.cancel} that wraps its whole request in a single {@code
 * @Transactional} method):
 *
 * <ul>
 *     <li>{@link #insert} — so a lost race against the unique constraint can be caught
 *     by {@link IdempotencyService#recordIfNew} without poisoning the caller's
 *     transaction.</li>
 *     <li>{@link #deleteIsolated} — so a delete this method performs is guaranteed
 *     visible (committed) to a subsequent, separately-isolated {@link #insert} call,
 *     rather than sitting uncommitted in the caller's still-open ambient transaction
 *     where a same-thread {@code REQUIRES_NEW} insert could block on it indefinitely.</li>
 * </ul>
 */
public interface IdempotencyKeyRepositoryCustom {

    /**
     * Inserts {@code record}, propagating a {@link
     * org.springframework.dao.DataIntegrityViolationException} on a unique-constraint
     * race rather than catching it — this method must let the failure cross its own
     * transactional boundary uncaught, so its {@code REQUIRES_NEW} transaction rolls
     * back cleanly instead of being caught-internally-but-left-marked-rollback-only
     * (the classic Spring pitfall this class exists to avoid).
     *
     * @param record the key record to insert
     */
    void insert(IdempotencyKeyRecord record);

    /**
     * Deletes the key record with the given id, if it still exists, committing
     * immediately in its own transaction. A no-op if already gone (e.g. a concurrent
     * caller deleted the same expired record first).
     *
     * @param id the {@link IdempotencyKeyRecord}'s id
     */
    void deleteIsolated(UUID id);
}
