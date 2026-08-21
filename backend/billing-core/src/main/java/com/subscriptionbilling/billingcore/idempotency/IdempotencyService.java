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
     * <p>Deliberately not wrapped in a single {@code @Transactional}: the read and the
     * write are each already transactional (Spring Data's per-repository-method default),
     * and keeping them separate means a lost race's constraint-violation rollback stays
     * confined to the failed insert's own transaction instead of poisoning this method's.
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
            repository.delete(existing.get());
            repository.flush();
        }
        try {
            repository.saveAndFlush(new IdempotencyKeyRecord(
                    UUID.randomUUID(), customerId, operation, idempotencyKey, now, now.plus(retention)));
            return true;
        } catch (DataIntegrityViolationException lostRace) {
            return false;
        }
    }
}
