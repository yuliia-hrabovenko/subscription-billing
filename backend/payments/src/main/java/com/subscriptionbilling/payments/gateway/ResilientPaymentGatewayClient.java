package com.subscriptionbilling.payments.gateway;

import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * Infra-level resilience wrapper around a {@link PaymentGatewayClient} adapter's {@link
 * #charge}: a bounded Resilience4j {@link Retry} absorbs a single transient gateway
 * failure, and a {@link CircuitBreaker} stops an unbounded pile of retrying calls once
 * failures keep repeating, per {@link GatewayResilienceProperties}. This policy is
 * deliberately distinct from, and must never be conflated with, Dunning's separate
 * business-level retry schedule for a declined charge.
 *
 * <p>Both mechanisms are scoped strictly to {@link ChargeResult.FailedTransiently}: a
 * {@link ChargeResult.Declined} result is a definitive business outcome, so it is never
 * retried and never recorded against the breaker, and passes straight through to the
 * caller for Dunning hand-off. The breaker wraps the retry rather than the reverse, so a
 * retry that exhausts its bounded attempts is recorded as one failed call against the
 * breaker, not one per attempt.
 *
 * <p>{@link #verifyWebhookSignature} is delegated straight through, unwrapped — it is not
 * part of this ticket's resilience scope.
 */
public class ResilientPaymentGatewayClient implements PaymentGatewayClient {

    private static final String RESILIENCE_NAME = "payment-gateway-infra-transient-failure";

    private final PaymentGatewayClient delegate;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public ResilientPaymentGatewayClient(PaymentGatewayClient delegate, GatewayResilienceProperties properties) {
        this(delegate, buildRetry(properties.getRetry()), buildCircuitBreaker(properties.getCircuitBreaker()));
    }

    ResilientPaymentGatewayClient(PaymentGatewayClient delegate, Retry retry, CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.retry = retry;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Charges via the delegate, retrying a {@link ChargeResult.FailedTransiently} result up
     * to the bounded policy's attempt limit, then recording the single final outcome
     * against the circuit breaker. A {@link ChargeResult.Declined} result short-circuits
     * both the retry and the breaker.
     *
     * @param paymentMethodToken the Customer's gateway-provided card-on-file reference
     * @param providerCustomerId the gateway's identity for the Customer this card is attached to
     * @param amount             the amount to charge, in the account's single billing currency
     * @return the delegate's resolved outcome, or {@link ChargeResult.FailedTransiently} if the circuit is open
     */
    @Override
    public ChargeResult charge(String paymentMethodToken, String providerCustomerId, BigDecimal amount) {
        Supplier<ChargeResult> withRetry = Retry.decorateSupplier(retry, () -> delegate.charge(paymentMethodToken, providerCustomerId, amount));
        Supplier<ChargeResult> withCircuitBreaker = CircuitBreaker.decorateSupplier(circuitBreaker, withRetry);
        try {
            return withCircuitBreaker.get();
        } catch (CallNotPermittedException circuitOpen) {
            return new ChargeResult.FailedTransiently("circuit_open");
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signatureHeader) {
        return delegate.verifyWebhookSignature(payload, signatureHeader);
    }

    /**
     * @return the breaker's current state, for tests and diagnostics
     */
    public CircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker.getState();
    }

    private static boolean isTransientFailure(ChargeResult result) {
        return result instanceof ChargeResult.FailedTransiently;
    }

    private static Retry buildRetry(GatewayResilienceProperties.RetrySettings settings) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(settings.getMaxAttempts())
                .waitDuration(settings.getWaitDuration())
                .retryOnResult(result -> isTransientFailure((ChargeResult) result))
                .retryOnException(thrown -> false)
                .build();
        return Retry.of(RESILIENCE_NAME, config);
    }

    private static CircuitBreaker buildCircuitBreaker(GatewayResilienceProperties.CircuitBreakerSettings settings) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(settings.getSlidingWindowSize())
                .minimumNumberOfCalls(settings.getMinimumNumberOfCalls())
                .failureRateThreshold(settings.getFailureRateThreshold())
                .waitDurationInOpenState(settings.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.getPermittedNumberOfCallsInHalfOpenState())
                .recordResult(result -> isTransientFailure((ChargeResult) result))
                .build();
        return CircuitBreaker.of(RESILIENCE_NAME, config);
    }
}
