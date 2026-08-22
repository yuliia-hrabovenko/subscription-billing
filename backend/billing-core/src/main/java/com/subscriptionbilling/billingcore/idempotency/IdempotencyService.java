package com.subscriptionbilling.billingcore.idempotency;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Dedupes a client-supplied {@code Idempotency-Key} per customer and operation within a
 * bounded retention window, for use by state-changing endpoints (plan-change, cancel,
 * undo-cancel) — not signup.
 */
@Service
public class IdempotencyService {

    private static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    private final IdempotencyKeyRepository repository;
    private final Clock clock;
    private final Duration retention;

    @Autowired
    public IdempotencyService(IdempotencyKeyRepository repository) {
        this(repository, Clock.systemUTC(), DEFAULT_RETENTION);
    }

    IdempotencyService(IdempotencyKeyRepository repository, Clock clock, Duration retention) {
        this.repository = repository;
        this.clock = clock;
        this.retention = retention;
    }

    /**
     * Records the key if it hasn't been seen (for this customer + operation) within the
     * retention window. Returns {@code true} the first time — the caller should proceed.
     * Returns {@code false} on a duplicate — the caller should treat the request as
     * already applied. A concurrent race for the same key is resolved by the database's
     * unique constraint: exactly one caller wins {@code true}.
     *
     * <p>Deliberately not itself {@code @Transactional}: the insert attempt below runs
     * through {@link IdempotencyKeyRepositoryCustom#insert}, which owns its own {@code
     * REQUIRES_NEW} transaction and lets a lost race propagate out of that transaction's
     * boundary uncaught — this method only catches the (by then fully rolled-back)
     * result. This split matters regardless of whether a caller like {@code
     * SubscriptionService.cancel} already has its own transaction open: catching the
     * exception in the *same* method that owns the transaction would leave that
     * transaction marked rollback-only despite returning normally, which Spring reports
     * as {@code UnexpectedRollbackException} at commit — see {@link
     * IdempotencyKeyRepositoryImpl}'s Javadoc for why the two responsibilities must live
     * in different transactional boundaries.
     */
    public boolean recordIfNew(UUID customerId, String operation, String idempotencyKey) {
        Instant now = Instant.now(clock);
        Optional<IdempotencyKeyRecord> existing =
                repository.findByCustomerIdAndOperationAndIdempotencyKey(customerId, operation, idempotencyKey);
        if (existing.isPresent()) {
            if (existing.get().getExpiresAt().isAfter(now)) {
                return false;
            }
            // Expired: the unique constraint would otherwise block reuse of this key
            // forever, contradicting the "bounded retention window" this class promises.
            // Isolated (REQUIRES_NEW) and thus committed before the insert below runs its
            // own separate transaction: without that, a caller with an ambient
            // transaction already open (e.g. SubscriptionService.cancel) would leave this
            // delete uncommitted-but-flushed in that transaction, invisible to the
            // insert's own connection under READ COMMITTED — which would then either see
            // the "expired" row as still present (falsely reporting a duplicate) or block
            // indefinitely on the row lock the suspended ambient transaction still holds.
            repository.deleteIsolated(existing.get().getId());
        }
        try {
            repository.insert(new IdempotencyKeyRecord(
                    UUID.randomUUID(), customerId, operation, idempotencyKey, now, now.plus(retention)));
            return true;
        } catch (DataIntegrityViolationException lostRace) {
            return false;
        }
    }

    /**
     * Releases a key this method previously recorded, for use when the operation that
     * key was guarding turned out not to apply after all (a domain-layer rejection, e.g.
     * canceling an already-{@code canceled} Subscription) — without this, the key would
     * stay recorded for a request that never actually succeeded, and a legitimate retry
     * of that same failing request would be wrongly short-circuited into a fabricated
     * success instead of seeing the same rejection again.
     *
     * <p>Runs in its own isolated, immediately-committed transaction (see {@link
     * IdempotencyKeyRepositoryCustom#deleteIsolated}) so the release survives regardless
     * of whether the caller's own ambient transaction (which recorded the failed
     * operation's attempted changes) goes on to roll back.
     *
     * @param customerId     the Customer the key was recorded against
     * @param operation      the operation name the key was recorded against
     * @param idempotencyKey the key to release
     */
    public void release(UUID customerId, String operation, String idempotencyKey) {
        repository.findByCustomerIdAndOperationAndIdempotencyKey(customerId, operation, idempotencyKey)
                .ifPresent(record -> repository.deleteIsolated(record.getId()));
    }
}
