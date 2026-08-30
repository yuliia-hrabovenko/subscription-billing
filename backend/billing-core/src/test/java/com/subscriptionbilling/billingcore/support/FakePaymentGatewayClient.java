package com.subscriptionbilling.billingcore.support;

import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Placeholder {@link PaymentGatewayClient} for this module's full-context tests, standing
 * in for the real gateway adapter (billing-core intentionally has no dependency on the
 * {@code payments} module). Mirrors {@code billing-job}'s test-only {@code
 * FakePaymentGatewayClient} (not reusable here since it lives in that module's test
 * sources): the outcome is selected deterministically by {@code paymentMethodToken} so a
 * test can drive each {@link ChargeResult} case without stubbing an HTTP call. Any token
 * other than {@link #DECLINE_TOKEN} or {@link #TRANSIENT_FAILURE_TOKEN} succeeds.
 */
public class FakePaymentGatewayClient implements PaymentGatewayClient {

    /** Card-on-file token that always resolves to {@link ChargeResult.Declined}. */
    public static final String DECLINE_TOKEN = "tok_decline";

    /** Card-on-file token that always resolves to {@link ChargeResult.FailedTransiently}. */
    public static final String TRANSIENT_FAILURE_TOKEN = "tok_transient_failure";

    @Override
    public ChargeResult charge(String paymentMethodToken, BigDecimal amount) {
        if (DECLINE_TOKEN.equals(paymentMethodToken)) {
            return new ChargeResult.Declined("card_declined");
        }
        if (TRANSIENT_FAILURE_TOKEN.equals(paymentMethodToken)) {
            return new ChargeResult.FailedTransiently("gateway_timeout");
        }
        return new ChargeResult.Succeeded("fake-txn-" + UUID.randomUUID());
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signatureHeader) {
        throw new UnsupportedOperationException(
                "billing-core has no webhook ingestion path; this fake only satisfies PaymentGatewayClient's bean requirement for full-context tests");
    }
}
