package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.audit.ActorType;
import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingjob.anchor.BillingCycleAdvancePort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * This module's implementation of the billing-job module's {@link
 * BillingCycleAdvancePort} contract, backed by {@link Subscription#applySuccessfulCharge}.
 * Whether that call also converts a Trial (its Subscription is currently {@code
 * trialing}) or is an ordinary renewal's due-date advance (already {@code active}) is
 * decided entirely by the persisted Subscription's own state, not by anything this
 * class or its caller branches on. A resulting {@code trialing -> active} transition is
 * appended to the audit log (Invariant 12) in the same transaction as the state change;
 * a renewal, which transitions nothing, writes no entry.
 */
@Component
public class BillingCycleAdvanceAdapter implements BillingCycleAdvancePort {

    private final SubscriptionRepository subscriptionRepository;
    private final AuditLogEntryRepository auditLogEntryRepository;
    private final Clock clock;

    public BillingCycleAdvanceAdapter(SubscriptionRepository subscriptionRepository,
                                       AuditLogEntryRepository auditLogEntryRepository) {
        this(subscriptionRepository, auditLogEntryRepository, Clock.systemUTC());
    }

    BillingCycleAdvanceAdapter(SubscriptionRepository subscriptionRepository,
                                AuditLogEntryRepository auditLogEntryRepository, Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.auditLogEntryRepository = auditLogEntryRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void advanceDueDate(UUID subscriptionId, LocalDate nextDueDate, String correlationId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Subscription " + subscriptionId + " does not exist"));
        SubscriptionState oldState = subscription.getState();
        subscription.applySuccessfulCharge(Instant.now(clock), nextDueDate);
        subscriptionRepository.saveAndFlush(subscription);
        if (subscription.getState() != oldState) {
            auditLogEntryRepository.append(new AuditLogEntry(
                    UUID.randomUUID(), subscription.getId(), ActorType.SYSTEM, oldState.name(),
                    subscription.getState().name(), correlationId));
        }
    }
}
