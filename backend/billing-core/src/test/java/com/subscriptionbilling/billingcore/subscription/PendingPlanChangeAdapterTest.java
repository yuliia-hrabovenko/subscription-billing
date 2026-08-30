package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.audit.ActorType;
import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PriceVersion;
import com.subscriptionbilling.billingcore.plan.PriceVersionRepository;
import com.subscriptionbilling.billingjob.planchange.PendingPlanChangeOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
 * Unit coverage of {@link PendingPlanChangeAdapter}: it forwards to {@link
 * Subscription#applyPendingPlanChange}, deciding free vs. paid from the pending target
 * Plan's price as of the Subscription's due date, and appends an {@link AuditLogEntry}
 * only when a change was actually pending.
 */
@ExtendWith(MockitoExtension.class)
class PendingPlanChangeAdapterTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private PriceVersionRepository priceVersionRepository;

    @Mock
    private AuditLogEntryRepository auditLogEntryRepository;

    private final Customer customer = new Customer(UUID.randomUUID(), "plan-change@example.com");
    private final Plan currentPlan = new Plan(UUID.randomUUID(), "pro", "Pro");

    private PendingPlanChangeAdapter adapter() {
        return new PendingPlanChangeAdapter(subscriptionRepository, priceVersionRepository, auditLogEntryRepository);
    }

    @Test
    void applyIfPendingWithNothingPendingReturnsNoPendingChangeAndWritesNoAuditEntry() {
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, currentPlan, Instant.now());
        subscription.advanceDueDate(LocalDate.of(2026, 8, 24));
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));

        PendingPlanChangeOutcome outcome = adapter().applyIfPending(subscription.getId(), "corr-1");

        assertThat(outcome).isEqualTo(PendingPlanChangeOutcome.NO_PENDING_CHANGE);
        assertThat(subscription.getPlan()).isEqualTo(currentPlan);
        verify(subscriptionRepository, never()).saveAndFlush(any());
        verify(auditLogEntryRepository, never()).append(any());
    }

    @Test
    void applyIfPendingAppliesAPaidToPaidChangeSwitchesThePlanAndWritesAnAuditEntry() {
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, currentPlan, Instant.now());
        LocalDate dueDate = LocalDate.of(2026, 8, 24);
        subscription.advanceDueDate(dueDate);
        Plan targetPlan = new Plan(UUID.randomUUID(), "enterprise", "Enterprise");
        subscription.schedulePlanChange(targetPlan, false, Instant.now());
        PriceVersion targetPrice = new PriceVersion(
                UUID.randomUUID(), targetPlan, new BigDecimal("49.00"), Instant.parse("2026-01-01T00:00:00Z"));
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
        when(priceVersionRepository.findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                targetPlan.getId(), dueDate.atStartOfDay(ZoneOffset.UTC).toInstant())).thenReturn(Optional.of(targetPrice));

        PendingPlanChangeOutcome outcome = adapter().applyIfPending(subscription.getId(), "corr-2");

        assertThat(outcome).isEqualTo(PendingPlanChangeOutcome.APPLIED_PAID);
        assertThat(subscription.getPlan()).isEqualTo(targetPlan);
        assertThat(subscription.getPendingPlanChange()).isNull();
        assertThat(subscription.getDueDate()).isEqualTo(dueDate);
        verify(subscriptionRepository).saveAndFlush(subscription);

        ArgumentCaptor<AuditLogEntry> entryCaptor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(entryCaptor.capture());
        AuditLogEntry entry = entryCaptor.getValue();
        assertThat(entry.getSubscriptionId()).isEqualTo(subscription.getId());
        assertThat(entry.getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(entry.getOldState()).isEqualTo("pro");
        assertThat(entry.getNewState()).isEqualTo("enterprise");
        assertThat(entry.getCorrelationId()).isEqualTo("corr-2");
    }

    @Test
    void applyIfPendingAppliesADowngradeToFreeClearsTheBillingCycleAndDueDate() {
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, currentPlan, Instant.now());
        LocalDate dueDate = LocalDate.of(2026, 8, 24);
        subscription.advanceDueDate(dueDate);
        Plan freePlan = new Plan(UUID.randomUUID(), "free", "Free");
        subscription.schedulePlanChange(freePlan, true, Instant.now());
        PriceVersion freePrice = new PriceVersion(
                UUID.randomUUID(), freePlan, BigDecimal.ZERO, Instant.parse("2026-01-01T00:00:00Z"));
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
        when(priceVersionRepository.findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                freePlan.getId(), dueDate.atStartOfDay(ZoneOffset.UTC).toInstant())).thenReturn(Optional.of(freePrice));

        PendingPlanChangeOutcome outcome = adapter().applyIfPending(subscription.getId(), "corr-3");

        assertThat(outcome).isEqualTo(PendingPlanChangeOutcome.APPLIED_FREE);
        assertThat(subscription.getPlan()).isEqualTo(freePlan);
        assertThat(subscription.getPendingPlanChange()).isNull();
        assertThat(subscription.getBillingCycleAnchor()).isNull();
        assertThat(subscription.getDueDate()).isNull();
        verify(subscriptionRepository).saveAndFlush(subscription);
        verify(auditLogEntryRepository).append(any());
    }

    @Test
    void failsFastWhenTheSubscriptionDoesNotExist() {
        UUID subscriptionId = UUID.randomUUID();
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter().applyIfPending(subscriptionId, "corr-4"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastWhenTheTargetPlanHasNoPriceVersionInEffect() {
        Subscription subscription = Subscription.startPaidImmediately(UUID.randomUUID(), customer, currentPlan, Instant.now());
        LocalDate dueDate = LocalDate.of(2026, 8, 24);
        subscription.advanceDueDate(dueDate);
        Plan targetPlan = new Plan(UUID.randomUUID(), "enterprise", "Enterprise");
        subscription.schedulePlanChange(targetPlan, false, Instant.now());
        when(subscriptionRepository.findById(subscription.getId())).thenReturn(Optional.of(subscription));
        when(priceVersionRepository.findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                targetPlan.getId(), dueDate.atStartOfDay(ZoneOffset.UTC).toInstant())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter().applyIfPending(subscription.getId(), "corr-5"))
                .isInstanceOf(IllegalStateException.class);
    }
}
