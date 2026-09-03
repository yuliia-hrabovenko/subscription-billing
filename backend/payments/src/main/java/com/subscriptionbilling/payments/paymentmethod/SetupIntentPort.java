package com.subscriptionbilling.payments.paymentmethod;

/**
 * Backs the signup-time endpoint the frontend calls before a Customer record exists, to
 * get a SetupIntent {@code client_secret} to confirm a card against via Stripe.js. Owned
 * directly by this module rather than defined in {@code billing-job}: {@code api} already
 * depends on {@code payments} directly (unlike {@code billing-core}), so there is no
 * cross-module decoupling need for this seam the way there is for
 * {@link com.subscriptionbilling.billingjob.paymentmethod.CustomerPaymentMethodPort}.
 */
public interface SetupIntentPort {

    /**
     * @return a new SetupIntent's {@code client_secret}, for the frontend to confirm a
     *         card against directly with Stripe
     */
    String createClientSecret();
}
