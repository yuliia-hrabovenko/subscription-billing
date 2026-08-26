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
 * <p>The stubbed {@code POST /v1/charges} endpoint branches on the {@code source} form
 * field: {@link #SUCCESS_TOKEN} returns a succeeded charge, {@link #DECLINED_TOKEN}
 * returns Stripe's {@code card_declined} error shape, and {@link #TIMEOUT_TOKEN} returns
 * a succeeded charge after a {@value #TIMEOUT_DELAY_MILLIS}ms delay so callers can
 * exercise timeout/circuit-breaker handling. {@link #DISPUTE_WEBHOOK_EVENT_PATH} serves a
 * fixed {@code charge.dispute.created} event payload for webhook-handling tests.
 */
public final class StripeGatewayStub {

    /** Path of the stubbed Stripe charge-creation endpoint. */
    public static final String CHARGES_PATH = "/v1/charges";

    /** Path serving a fixed Stripe {@code charge.dispute.created} webhook event payload. */
    public static final String DISPUTE_WEBHOOK_EVENT_PATH = "/stub-fixtures/webhook-events/dispute-created";

    /** {@code source} token value that yields a succeeded charge. */
    public static final String SUCCESS_TOKEN = "tok_visa";

    /** {@code source} token value that yields Stripe's card_declined error shape. */
    public static final String DECLINED_TOKEN = "tok_chargeDeclined";

    /** {@code source} token value that yields a delayed charge response. */
    public static final String TIMEOUT_TOKEN = "tok_timeout";

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
