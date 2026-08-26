package com.subscriptionbilling.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage of {@link ApiApplication}'s billing-job argument detection — no Spring
 * context, just the {@code --job=billing-run} dispatch rule itself.
 */
class ApiApplicationTest {

    @Test
    void jobArgumentAmongOthersIsDetected() {
        assertThat(ApiApplication.isBillingJobInvocation(
                new String[] {"--server.port=0", "--job=billing-run"})).isTrue();
    }

    @Test
    void noArgumentsIsNotABillingJobInvocation() {
        assertThat(ApiApplication.isBillingJobInvocation(new String[0])).isFalse();
    }

    @Test
    void anUnrelatedArgumentIsNotABillingJobInvocation() {
        assertThat(ApiApplication.isBillingJobInvocation(new String[] {"--server.port=9090"})).isFalse();
    }
}
