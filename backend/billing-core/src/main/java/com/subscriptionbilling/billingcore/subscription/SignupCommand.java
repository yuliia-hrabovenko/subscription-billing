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
 *                           existingCustomerId} is null; ignored otherwise
 * @param existingCustomerId null for a brand-new Customer, or the Customer identified
 *                           by a bearer token presented at signup (a re-subscribing
 *                           Customer)
 * @param useTrial           ignored for a free-Plan signup, which has no Trial concept
 * @param paymentMethodToken required whenever the target Plan is paid; ignored for a
 *                           free-Plan signup
 * @param password           used to set a brand-new Customer's login credential when
 *                           {@code existingCustomerId} is null; ignored otherwise,
 *                           mirroring {@code email}
 * @param correlationId      rides along on the {@link com.subscriptionbilling.audit.AuditLogEntry}
 *                           this signup writes
 */
public record SignupCommand(UUID planId, String email, UUID existingCustomerId, boolean useTrial,
                             String paymentMethodToken, String password, String correlationId) {
}
