package com.subscriptionbilling.billingcore.subscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link TrialEndingSoonAdapter}: it forwards to {@link
 * SubscriptionRepository#findTrialsEndingSoonIds} (always querying {@link
 * SubscriptionState#TRIALING}) and to {@link SubscriptionService#notifyTrialEndingSoon}.
 * {@link SubscriptionServiceTest} covers what the latter actually does.
 */
@ExtendWith(MockitoExtension.class)
class TrialEndingSoonAdapterTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private SubscriptionService subscriptionService;

    private TrialEndingSoonAdapter adapter() {
        return new TrialEndingSoonAdapter(subscriptionRepository, subscriptionService);
    }

    @Test
    void findTrialsEndingSoonDelegatesToTheRepositoryQueryingTheTrialingState() {
        Instant leadTimeCutoff = Instant.parse("2026-09-01T00:00:00Z");
        UUID candidateId = UUID.randomUUID();
        when(subscriptionRepository.findTrialsEndingSoonIds(SubscriptionState.TRIALING, leadTimeCutoff))
                .thenReturn(List.of(candidateId));

        List<UUID> result = adapter().findTrialsEndingSoon(leadTimeCutoff);

        assertThat(result).containsExactly(candidateId);
    }

    @Test
    void notifyTrialEndingSoonDelegatesToSubscriptionService() {
        UUID subscriptionId = UUID.randomUUID();
        Instant notifiedAt = Instant.parse("2026-09-01T00:00:00Z");

        adapter().notifyTrialEndingSoon(subscriptionId, notifiedAt);

        verify(subscriptionService).notifyTrialEndingSoon(subscriptionId, notifiedAt);
    }
}
