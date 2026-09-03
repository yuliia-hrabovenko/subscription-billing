package com.subscriptionbilling.payments.paymentmethod;

/**
 * The gateway rejected onboarding a payment method -- a network-level failure, a
 * non-2xx/malformed gateway response, or the gateway declining the attach outright.
 * Unlike {@link com.subscriptionbilling.billingjob.gateway.ChargeResult}, onboarding has
 * no meaningful three-way split to model: there is no business outcome to hand off to
 * Dunning here, only "it worked" or "it didn't," so this is a thrown exception rather
 * than a result type.
 */
public class PaymentMethodAttachmentException extends RuntimeException {

    public PaymentMethodAttachmentException(String message) {
        super(message);
    }

    public PaymentMethodAttachmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
