package com.subscriptionbilling.api.support;

import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Token-driven {@link PaymentGatewayClient} fake wired by {@link PaymentGatewayTestConfig},
 * counting invocations so a test can assert the gateway was actually called at most once
 * across multiple {@code BillingJobRunner#run()} calls — the observable proof ticket #13's
 * duplicate-run guarantee stops a second gateway call, not only a second database write.
 */
public class CountingPaymentGatewayClient implements PaymentGatewayClient {

    /** Signature header value that always verifies successfully. Any other value fails. */
    public static final String VALID_SIGNATURE_HEADER = "valid_signature";

    private final String declineToken;
    private final String transientFailureToken;
    private final AtomicInteger chargeCount = new AtomicInteger();

    public CountingPaymentGatewayClient(String declineToken, String transientFailureToken) {
        this.declineToken = declineToken;
        this.transientFailureToken = transientFailureToken;
    }

    @Override
    public ChargeResult charge(String paymentMethodToken, String providerCustomerId, BigDecimal amount) {
        chargeCount.incrementAndGet();
        if (declineToken.equals(paymentMethodToken)) {
            return new ChargeResult.Declined("card_declined");
        }
        if (transientFailureToken.equals(paymentMethodToken)) {
            return new ChargeResult.FailedTransiently("gateway_timeout");
        }
        return new ChargeResult.Succeeded("test-txn-" + UUID.randomUUID());
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signatureHeader) {
        return VALID_SIGNATURE_HEADER.equals(signatureHeader);
    }

    /** @return the number of {@link #charge} calls made so far across this test's Spring context. */
    public int chargeCount() {
        return chargeCount.get();
    }
}
