package com.subscriptionbilling.payments.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Infra-level resilience settings for {@link ResilientPaymentGatewayClient}'s bounded
 * retry and circuit breaker around a single {@code charge} call. Scoped strictly to a
 * transient gateway/network failure (timeout, 5xx) at the HTTP-call layer; deliberately
 * separate from, and never merged with, Dunning's business-level 3-retries-over-7-days
 * recovery schedule for a declined charge.
 */
@ConfigurationProperties(prefix = "billing.payments.gateway-resilience")
public class GatewayResilienceProperties {

    private final RetrySettings retry = new RetrySettings();
    private final CircuitBreakerSettings circuitBreaker = new CircuitBreakerSettings();

    public RetrySettings getRetry() {
        return retry;
    }

    public CircuitBreakerSettings getCircuitBreaker() {
        return circuitBreaker;
    }

    /** Bounded-retry policy for a single transient gateway failure. */
    public static class RetrySettings {

        private int maxAttempts = 2;
        private Duration waitDuration = Duration.ofMillis(200);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getWaitDuration() {
            return waitDuration;
        }

        public void setWaitDuration(Duration waitDuration) {
            this.waitDuration = waitDuration;
        }
    }

    /** Circuit-breaker policy that trips once transient gateway failures repeat. */
    public static class CircuitBreakerSettings {

        private int slidingWindowSize = 10;
        private int minimumNumberOfCalls = 5;
        private float failureRateThreshold = 50f;
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);
        private int permittedNumberOfCallsInHalfOpenState = 3;

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedNumberOfCallsInHalfOpenState() {
            return permittedNumberOfCallsInHalfOpenState;
        }

        public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        }
    }
}
