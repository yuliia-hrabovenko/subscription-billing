package com.subscriptionbilling.webhooks.ingestion;

/**
 * A payment-succeeded/failed webhook event reports an outcome that conflicts with what
 * the synchronous charge path already recorded for the same gateway reference (e.g. the
 * gateway reports {@code failed} for a charge already recorded {@code succeeded}, or vice
 * versa) — a gateway data inconsistency worth surfacing, not something to guess through
 * by silently overwriting the prior outcome.
 */
public class ConflictingPaymentOutcomeException extends RuntimeException {

    public ConflictingPaymentOutcomeException(String message) {
        super(message);
    }
}
