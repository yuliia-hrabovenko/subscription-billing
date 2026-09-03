package com.subscriptionbilling.payments.stub;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Builds the shared WireMock double for Stripe test mode: the request/response shapes
 * every module that touches payments (billing job, dunning, webhooks) tests against
 * instead of defining its own gateway mock. Depend on this module's test-jar and call
 * {@link #newExtension()} from a {@code @RegisterExtension} field to get a WireMock
 * instance preloaded with the mappings below.
 *
 * <p>The stubbed {@code POST /v1/payment_intents} endpoint branches on the {@code
 * payment_method} form field: {@link #SUCCESS_TOKEN} returns a succeeded PaymentIntent,
 * {@link #DECLINED_TOKEN} returns Stripe's {@code card_declined} error shape, and {@link
 * #TIMEOUT_TOKEN} returns a succeeded PaymentIntent after a {@value
 * #TIMEOUT_DELAY_MILLIS}ms delay so callers can exercise timeout/circuit-breaker
 * handling. {@link #DISPUTE_WEBHOOK_EVENT_PATH} serves a fixed {@code
 * charge.dispute.created} event payload for webhook-handling tests -- disputes stay
 * charge-shaped regardless of PaymentIntents.
 */
public final class StripeGatewayStub {

    /** Path of the stubbed Stripe PaymentIntent-creation/confirmation endpoint. */
    public static final String PAYMENT_INTENTS_PATH = "/v1/payment_intents";

    /** Path serving a fixed Stripe {@code charge.dispute.created} webhook event payload. */
    public static final String DISPUTE_WEBHOOK_EVENT_PATH = "/stub-fixtures/webhook-events/dispute-created";

    /** {@code payment_method} token value that yields a succeeded PaymentIntent. */
    public static final String SUCCESS_TOKEN = "pm_visa";

    /** {@code payment_method} token value that yields Stripe's card_declined error shape. */
    public static final String DECLINED_TOKEN = "pm_cardDeclined";

    /** {@code payment_method} token value that yields a delayed PaymentIntent response. */
    public static final String TIMEOUT_TOKEN = "pm_timeout";

    /** {@code customer} value tests pass alongside the tokens above. */
    public static final String CUSTOMER_ID = "cus_test123";

    /** Delay applied before the {@link #TIMEOUT_TOKEN} stub responds. */
    public static final long TIMEOUT_DELAY_MILLIS = 5000;

    private static final String MAPPINGS_CLASSPATH_ROOT = "wiremock";

    private StripeGatewayStub() {
    }

    /**
     * Builds a JUnit 5 extension for a WireMock instance on a dynamic port, preloaded
     * with this class's stub mappings from the classpath. Not yet registered or
     * started; assign the result to a {@code @RegisterExtension} field.
     *
     * @return an unregistered {@link WireMockExtension} configured with the shared stub mappings
     */
    public static WireMockExtension newExtension() {
        return WireMockExtension.newInstance()
                .options(wireMockConfig()
                        .dynamicPort()
                        .usingFilesUnderClasspath(MAPPINGS_CLASSPATH_ROOT))
                .build();
    }
}
