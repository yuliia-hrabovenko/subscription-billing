package com.subscriptionbilling.billingcore.subscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

/**
 * Unit coverage of {@link SubscriptionSuspensionAdapter}: it forwards to {@link
 * SubscriptionService#suspend}. {@link SubscriptionServiceTest} covers what that
 * actually does (the trialing/active edges and the written {@code AuditLogEntry}).
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
}
