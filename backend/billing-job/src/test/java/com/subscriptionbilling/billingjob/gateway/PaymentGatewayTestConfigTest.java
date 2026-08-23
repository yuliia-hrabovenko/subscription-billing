package com.subscriptionbilling.billingjob.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(PaymentGatewayTestConfig.class)
class PaymentGatewayTestConfigTest {

    @Autowired
    private PaymentGatewayClient paymentGatewayClient;

    @Test
    void wiresTheFakeAsTheActivePaymentGatewayClientBean() {
        assertThat(paymentGatewayClient).isInstanceOf(FakePaymentGatewayClient.class);
    }
}
