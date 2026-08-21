package com.subscriptionbilling.audit;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Deliberately extends the bare {@link Repository} marker, not {@code JpaRepository} or
 * {@code CrudRepository}: those expose {@code save()} (which updates on a known ID),
 * {@code delete()}, and {@code deleteById()}. This interface exposes exactly one write
 * operation ({@link #append(AuditLogEntry)}) — Invariant 12 requires no update or delete
 * path to exist in code at all, not merely by convention.
 */
public interface AuditLogEntryRepository extends Repository<AuditLogEntry, UUID>, AuditLogEntryRepositoryCustom {

    Optional<AuditLogEntry> findById(UUID id);
}
