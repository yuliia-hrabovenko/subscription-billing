package com.subscriptionbilling.billingcore.subscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.Mockito.verify;

/**
 * Unit coverage of {@link SubscriptionSuspensionAdapter}: it forwards to {@link
 * SubscriptionService#suspend} and {@link SubscriptionService#scheduleRetry}. {@link
 * SubscriptionServiceTest} covers what those actually do (the trialing/active edges,
 * the due-date move, and the written {@code AuditLogEntry}).
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionSuspensionAdapterTest {

    @Mock
    private SubscriptionService subscriptionService;

    @Test
    void suspendDelegatesToSubscriptionService() {
        UUID subscriptionId = UUID.randomUUID();

        new SubscriptionSuspensionAdapter(subscriptionService).suspend(subscriptionId, "corr-1");

        verify(subscriptionService).suspend(subscriptionId, "corr-1");
    }

    @Test
    void scheduleRetryDelegatesToSubscriptionService() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate retryDueDate = LocalDate.of(2027, 1, 2);

        new SubscriptionSuspensionAdapter(subscriptionService).scheduleRetry(subscriptionId, retryDueDate);

        verify(subscriptionService).scheduleRetry(subscriptionId, retryDueDate);
    }
}
