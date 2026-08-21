package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * Input to {@link SubscriptionService#signUpForFreePlan}. {@code existingCustomerId} is
 * null for a brand-new Customer and non-null when a bearer token was presented at
 * signup — ADR-0003's identity-bootstrap exception for a re-subscribing Customer.
 * {@code correlationId} rides along so the {@link
 * com.subscriptionbilling.audit.AuditLogEntry} this signup writes can be cross-referenced
 * with the request's structured logs and traces, same as every other audited write.
 */
public record FreeSignupCommand(UUID planId, String email, UUID existingCustomerId, String correlationId) {
}
