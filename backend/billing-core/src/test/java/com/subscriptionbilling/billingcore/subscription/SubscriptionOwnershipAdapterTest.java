package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.plan.Plan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link SubscriptionOwnershipAdapter}: matches the invoicing module's
 * {@code SubscriptionOwnershipPort} contract that a nonexistent Subscription and one
 * belonging to a different Customer both resolve to {@code false} rather than throwing —
 * the caller (the invoicing module) is what turns "false" into a uniform, existence-hiding
 * rejection.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionOwnershipAdapterTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    private final Customer customer = new Customer(UUID.randomUUID(), "owner@example.com");
    private final Plan plan = new Plan(UUID.randomUUID(), "pro", "Pro");

    @Test
    void returnsTrueWhenTheSubscriptionBelongsToTheGivenCustomer() {
        Subscription subscription = new Subscription(UUID.randomUUID(), customer, plan, SubscriptionState.ACTIVE);
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));

        boolean owned = new SubscriptionOwnershipAdapter(subscriptionRepository)
                .isOwnedByCustomer(subscription.getId(), customer.getId());

        assertThat(owned).isTrue();
    }

    @Test
    void returnsFalseWhenTheSubscriptionBelongsToADifferentCustomer() {
        Subscription subscription = new Subscription(UUID.randomUUID(), customer, plan, SubscriptionState.ACTIVE);
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));

        boolean owned = new SubscriptionOwnershipAdapter(subscriptionRepository)
                .isOwnedByCustomer(subscription.getId(), UUID.randomUUID());

        assertThat(owned).isFalse();
    }

    @Test
    void returnsFalseWhenNoSuchSubscriptionExistsRatherThanThrowing() {
        UUID subscriptionId = UUID.randomUUID();
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.empty());

        boolean owned = new SubscriptionOwnershipAdapter(subscriptionRepository)
                .isOwnedByCustomer(subscriptionId, customer.getId());

        assertThat(owned).isFalse();
    }
}
