package com.subscriptionbilling.audit;

/**
 * The only write surface {@link AuditLogEntryRepository} exposes: append, never update.
 */
public interface AuditLogEntryRepositoryCustom {

    AuditLogEntry append(AuditLogEntry entry);
}
