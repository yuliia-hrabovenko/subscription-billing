package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.audit.ActorType;
import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PriceVersion;
import com.subscriptionbilling.billingcore.plan.PriceVersionRepository;
import com.subscriptionbilling.billingjob.planchange.PendingPlanChangeOutcome;
import com.subscriptionbilling.billingjob.planchange.PendingPlanChangePort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.UUID;

/**
 * This module's implementation of the billing-job module's {@link PendingPlanChangePort}
 * contract, backed by {@link Subscription#applyPendingPlanChange}. Prices the pending
 * target Plan as of the Subscription's due date — the same cutoff {@link
 * ChargeableSubscriptionAdapter} charges against — so a several-days-overdue catch-up
 * applies the change and charges using the price that was in effect on its own billing
 * period, not whatever is current today. The resulting Plan change is appended to the
 * audit log in the same transaction as the change itself, distinct from the entry
 * written when the change was originally scheduled.
 */
@Component
public class PendingPlanChangeAdapter implements PendingPlanChangePort {

    private final SubscriptionRepository subscriptionRepository;
    private final PriceVersionRepository priceVersionRepository;
    private final AuditLogEntryRepository auditLogEntryRepository;

    public PendingPlanChangeAdapter(SubscriptionRepository subscriptionRepository,
                                     PriceVersionRepository priceVersionRepository,
                                     AuditLogEntryRepository auditLogEntryRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.priceVersionRepository = priceVersionRepository;
        this.auditLogEntryRepository = auditLogEntryRepository;
    }

    @Override
    @Transactional
    public PendingPlanChangeOutcome applyIfPending(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Subscription " + subscriptionId + " does not exist"));
        Plan targetPlan = subscription.getPendingPlanChange();
        if (targetPlan == null) {
            return PendingPlanChangeOutcome.NO_PENDING_CHANGE;
        }

        Plan previousPlan = subscription.getPlan();
        boolean targetPlanIsFree = currentPrice(targetPlan, subscription).getAmount().signum() == 0;
        subscription.applyPendingPlanChange(targetPlanIsFree);
        subscriptionRepository.saveAndFlush(subscription);

        auditLogEntryRepository.append(new AuditLogEntry(
                UUID.randomUUID(), subscription.getId(), ActorType.SYSTEM, previousPlan.getCode(),
                targetPlan.getCode(), UUID.randomUUID().toString()));

        return targetPlanIsFree ? PendingPlanChangeOutcome.APPLIED_FREE : PendingPlanChangeOutcome.APPLIED_PAID;
    }

    private PriceVersion currentPrice(Plan plan, Subscription subscription) {
        return priceVersionRepository
                .findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        plan.getId(), subscription.getDueDate().atStartOfDay(ZoneOffset.UTC).toInstant())
                .orElseThrow(() -> new IllegalStateException("Plan " + plan.getId() + " has no PriceVersion in effect"));
    }
}
