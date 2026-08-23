package com.subscriptionbilling.billingjob.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Placeholder {@link PaymentGatewayClient} for developing and testing the billing job
 * before the real gateway adapter exists. Never calls an actual gateway; the outcome is
 * selected deterministically by {@code paymentMethodToken} so a test can drive each {@link
 * ChargeResult} case without stubbing an HTTP call. Any token other than {@link
 * #DECLINE_TOKEN} or {@link #TRANSIENT_FAILURE_TOKEN} succeeds. Confined to test sources so
 * it can never be mistaken for, or deployed as, the production adapter.
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
}
