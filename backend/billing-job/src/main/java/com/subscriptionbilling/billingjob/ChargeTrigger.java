package com.subscriptionbilling.billingjob;

/**
 * Distinguishes a scheduled, system-driven charge attempt from one a Customer triggered
 * directly (a self-service Dunning retry), so a port implementation that writes an
 * {@code AuditLogEntry} for a resulting Subscription state transition can attribute it
 * correctly without this module depending on the {@code audit} module's {@code
 * ActorType} itself.
 */
public enum ChargeTrigger {
    SYSTEM,
    CUSTOMER
}
