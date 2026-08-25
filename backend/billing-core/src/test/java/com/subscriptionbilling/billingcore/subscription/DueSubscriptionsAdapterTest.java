package com.subscriptionbilling.billingcore.subscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link DueSubscriptionsAdapter}: it forwards to {@link
 * SubscriptionRepository#findDueSubscriptionIds}. Whether the {@code <= asOf} comparison
 * itself is correct against real persisted Subscriptions is covered by the api module's
 * {@code BillingJobRunnerIT} (relocated there once success-path charging pulled in a
 * port only the invoicing module implements — see {@code BillingCoreTestApplication}'s
 * Javadoc).
 */
@ExtendWith(MockitoExtension.class)
class DueSubscriptionsAdapterTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @InjectMocks
    private DueSubscriptionsAdapter dueSubscriptionsAdapter;

    @Test
    void findDueSubscriptionIdsDelegatesToTheRepository() {
        LocalDate asOf = LocalDate.of(2026, 8, 24);
        UUID dueSubscriptionId = UUID.randomUUID();
        when(subscriptionRepository.findDueSubscriptionIds(asOf)).thenReturn(List.of(dueSubscriptionId));

        List<UUID> result = dueSubscriptionsAdapter.findDueSubscriptionIds(asOf);

        assertThat(result).containsExactly(dueSubscriptionId);
    }
}
