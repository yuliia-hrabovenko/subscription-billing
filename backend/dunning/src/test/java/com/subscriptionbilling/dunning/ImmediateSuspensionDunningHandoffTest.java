package com.subscriptionbilling.dunning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

/**
 * Unit coverage of {@link ImmediateSuspensionDunningHandoff}: it forwards a failed
 * charge to {@link SubscriptionSuspensionPort#suspend} with the failed charge's
 * Subscription and Invoice ids. Whether the suspension itself succeeds for a {@code
 * trialing} or {@code active} Subscription is the port implementation's own contract,
 * covered where that implementation lives.
 */
@ExtendWith(MockitoExtension.class)
class ImmediateSuspensionDunningHandoffTest {

    @Mock
    private SubscriptionSuspensionPort subscriptionSuspensionPort;

    @Test
    void onChargeFailedSuspendsTheSubscriptionThroughThePortUsingTheInvoiceIdAsTheCorrelationId() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();

        new ImmediateSuspensionDunningHandoff(subscriptionSuspensionPort).onChargeFailed(subscriptionId, invoiceId);

        verify(subscriptionSuspensionPort).suspend(subscriptionId, invoiceId.toString());
    }
}
