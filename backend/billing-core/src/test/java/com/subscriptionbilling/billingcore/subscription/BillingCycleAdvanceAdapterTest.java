package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.plan.Plan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link BillingCycleAdvanceAdapter}: it forwards to {@link
 * Subscription#advanceDueDate} and persists the result.
 */
@ExtendWith(MockitoExtension.class)
class BillingCycleAdvanceAdapterTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Test
    void advanceDueDatePersistsTheNewDueDateOnTheSubscription() {
        Customer customer = new Customer(UUID.randomUUID(), "advance@example.com");
        Plan plan = new Plan(UUID.randomUUID(), "pro", "Pro");
        Subscription subscription = new Subscription(
                UUID.randomUUID(), customer, plan, SubscriptionState.ACTIVE,
                Instant.parse("2026-01-31T00:00:00Z"), LocalDate.of(2026, 7, 31));
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));

        new BillingCycleAdvanceAdapter(subscriptionRepository)
                .advanceDueDate(subscription.getId(), LocalDate.of(2026, 8, 31));

        assertThat(subscription.getDueDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        verify(subscriptionRepository).saveAndFlush(subscription);
    }

    @Test
    void failsFastWhenTheSubscriptionDoesNotExist() {
        UUID subscriptionId = UUID.randomUUID();
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new BillingCycleAdvanceAdapter(subscriptionRepository)
                .advanceDueDate(subscriptionId, LocalDate.of(2026, 8, 31)))
                .isInstanceOf(IllegalStateException.class);
    }
}
