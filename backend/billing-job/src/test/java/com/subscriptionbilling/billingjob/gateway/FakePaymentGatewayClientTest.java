package com.subscriptionbilling.billingjob.gateway;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FakePaymentGatewayClientTest {

    private final FakePaymentGatewayClient client = new FakePaymentGatewayClient();

    @Test
    void succeedsForAnOrdinaryTokenWithAGatewayTransactionId() {
        ChargeResult result = client.charge("tok_visa", BigDecimal.TEN);

        assertThat(result).isInstanceOf(ChargeResult.Succeeded.class);
        assertThat(((ChargeResult.Succeeded) result).gatewayTransactionId()).isNotBlank();
    }

    @Test
    void declinesWithAReasonForTheDeclineToken() {
        ChargeResult result = client.charge(FakePaymentGatewayClient.DECLINE_TOKEN, BigDecimal.TEN);

        assertThat(result).isInstanceOf(ChargeResult.Declined.class);
        assertThat(((ChargeResult.Declined) result).reason()).isNotBlank();
    }

    @Test
    void failsTransientlyWithAReasonForTheTransientFailureToken() {
        ChargeResult result = client.charge(FakePaymentGatewayClient.TRANSIENT_FAILURE_TOKEN, BigDecimal.TEN);

        assertThat(result).isInstanceOf(ChargeResult.FailedTransiently.class);
        assertThat(((ChargeResult.FailedTransiently) result).reason()).isNotBlank();
    }

    @Test
    void verifiesTheValidSignatureHeader() {
        boolean verified = client.verifyWebhookSignature("{}", FakePaymentGatewayClient.VALID_SIGNATURE_HEADER);

        assertThat(verified).isTrue();
    }

    @Test
    void rejectsAnyOtherSignatureHeader() {
        boolean verified = client.verifyWebhookSignature("{}", "tampered_signature");

        assertThat(verified).isFalse();
    }
}
