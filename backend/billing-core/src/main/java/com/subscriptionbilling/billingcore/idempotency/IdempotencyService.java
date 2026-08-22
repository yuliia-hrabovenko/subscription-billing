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
     * Returns {@code true} the first time a key is seen (for this customer + operation)
     * within the retention window — the caller should proceed. Returns {@code false} on
     * a duplicate, resolving a concurrent race via the database's unique constraint.
     *
     * <p>Deliberately not itself {@code @Transactional}: the insert runs through {@link
     * IdempotencyKeyRepositoryCustom#insert}'s own {@code REQUIRES_NEW} transaction and
     * lets a lost race propagate out of it uncaught. Catching the exception in the same
     * method that owns the transaction would instead leave it marked rollback-only
     * despite returning normally — Spring reports that as {@code
     * UnexpectedRollbackException} at commit, regardless of whether the caller (e.g.
     * {@code SubscriptionService.cancel}) already had its own transaction open.
     */
    public boolean recordIfNew(UUID customerId, String operation, String idempotencyKey) {
        Instant now = Instant.now(clock);
        Optional<IdempotencyKeyRecord> existing =
                repository.findByCustomerIdAndOperationAndIdempotencyKey(customerId, operation, idempotencyKey);
        if (existing.isPresent()) {
            if (existing.get().getExpiresAt().isAfter(now)) {
                return false;
            }
            // Expired: the unique constraint would otherwise block reuse forever. Isolated
            // (REQUIRES_NEW) so it's committed before the insert below runs its own
            // separate transaction — otherwise, inside an ambient transaction (e.g.
            // SubscriptionService.cancel), this delete would stay uncommitted and
            // invisible to the insert's connection under READ COMMITTED, which would then
            // either see the "expired" row as still present or block on its lock.
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
     * Releases a key this method previously recorded, for when the operation it was
     * guarding turned out not to apply after all (a domain-layer rejection) — otherwise
     * a legitimate retry of that same failing request would be wrongly short-circuited
     * into a fabricated success. Isolated and immediately committed (see {@link
     * IdempotencyKeyRepositoryCustom#deleteIsolated}) so the release survives even if
     * the caller's own ambient transaction goes on to roll back.
     *
     * @param customerId     the Customer the key was recorded against
     * @param operation      the operation name the key was recorded against
     * @param idempotencyKey the key to release
     */
    public void release(UUID customerId, String operation, String idempotencyKey) {
        repository.deleteIsolated(customerId, operation, idempotencyKey);
    }
}
