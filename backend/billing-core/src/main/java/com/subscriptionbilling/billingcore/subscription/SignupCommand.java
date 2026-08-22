package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * Input to {@link SubscriptionService#signUp}, covering all three signup paths (free,
 * Trial, immediate-paid) — which path is taken is decided from this command's data
 * (the target Plan's price, and {@code useTrial}), not from three separate command
 * types.
 *
 * @param planId             the Plan being signed up for
 * @param email              used to mint a brand-new Customer when {@code
 *                           existingCustomerId} is null; accepted but ignored otherwise
 * @param existingCustomerId null for a brand-new Customer, or the Customer identified
 *                           by a bearer token presented at signup — the one case a
 *                           re-subscribing Customer attaches a new Subscription to
 *                           their existing Customer record instead of minting a second
 *                           one
 * @param useTrial           true to enter a Trial instead of being charged immediately;
 *                           ignored for a free-Plan signup, which has no Trial concept
 * @param paymentMethodToken a gateway-provided card reference, required whenever the
 *                           target Plan is paid (Trial or immediate-paid) and ignored
 *                           for a free-Plan signup
 * @param correlationId      rides along so the {@link com.subscriptionbilling.audit.AuditLogEntry}
 *                           this signup writes can be cross-referenced with the
 *                           request's structured logs and traces, same as every other
 *                           audited write
 */
public record SignupCommand(UUID planId, String email, UUID existingCustomerId, boolean useTrial,
                             String paymentMethodToken, String correlationId) {
}
