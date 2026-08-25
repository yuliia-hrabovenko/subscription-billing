package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.audit.ActorType;
import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.plan.Plan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link BillingCycleAdvanceAdapter}: it forwards to {@link
 * Subscription#applySuccessfulCharge} and persists the result, appending an
 * {@link AuditLogEntry} only when that call actually transitioned the Subscription
 * (a Trial conversion) and not for an ordinary renewal's plain due-date advance.
 */
@ExtendWith(MockitoExtension.class)
class BillingCycleAdvanceAdapterTest {

    private static final Instant CHARGED_AT = Instant.parse("2026-08-24T03:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(CHARGED_AT, ZoneOffset.UTC);

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private AuditLogEntryRepository auditLogEntryRepository;

    private final Customer customer = new Customer(UUID.randomUUID(), "advance@example.com");
    private final Plan plan = new Plan(UUID.randomUUID(), "pro", "Pro");

    private BillingCycleAdvanceAdapter adapter() {
        return new BillingCycleAdvanceAdapter(subscriptionRepository, auditLogEntryRepository, FIXED_CLOCK);
    }

    @Test
    void advanceDueDatePersistsTheNewDueDateOnAnAlreadyActiveSubscriptionAndWritesNoAuditEntry() {
        Subscription subscription = new Subscription(
                UUID.randomUUID(), customer, plan, SubscriptionState.ACTIVE,
                Instant.parse("2026-01-31T00:00:00Z"), LocalDate.of(2026, 7, 31));
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));

        adapter().advanceDueDate(subscription.getId(), LocalDate.of(2026, 8, 31), "gw-txn-1");

        assertThat(subscription.getDueDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(subscription.getState()).isEqualTo(SubscriptionState.ACTIVE);
        verify(subscriptionRepository).saveAndFlush(subscription);
        verify(auditLogEntryRepository, never()).append(any());
    }

    @Test
    void advancingADueTrialingSubscriptionActivatesItOpensTheBillingCycleAndAppendsAnAuditEntry() {
        Subscription subscription = Subscription.startTrial(UUID.randomUUID(), customer, plan, Instant.now());
        subscription.advanceDueDate(LocalDate.of(2026, 8, 24));
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));

        adapter().advanceDueDate(subscription.getId(), LocalDate.of(2026, 9, 24), "gw-txn-trial");

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(subscription.getBillingCycleAnchor()).isEqualTo(CHARGED_AT);
        assertThat(subscription.getTrialEndsAt()).isNull();
        assertThat(subscription.getDueDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        verify(subscriptionRepository).saveAndFlush(subscription);

        ArgumentCaptor<AuditLogEntry> entryCaptor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(entryCaptor.capture());
        AuditLogEntry entry = entryCaptor.getValue();
        assertThat(entry.getSubscriptionId()).isEqualTo(subscription.getId());
        assertThat(entry.getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(entry.getOldState()).isEqualTo("TRIALING");
        assertThat(entry.getNewState()).isEqualTo("ACTIVE");
        assertThat(entry.getCorrelationId()).isEqualTo("gw-txn-trial");
    }

    @Test
    void failsFastWhenTheSubscriptionDoesNotExist() {
        UUID subscriptionId = UUID.randomUUID();
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter().advanceDueDate(subscriptionId, LocalDate.of(2026, 8, 31), "gw-txn-1"))
                .isInstanceOf(IllegalStateException.class);
    }
}
