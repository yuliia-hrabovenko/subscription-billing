package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.audit.ActorType;
import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.auth.CustomerTokenIssuer;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.plan.PlanSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The persistence tail of {@link SubscriptionService#signUp}, split into its own bean
 * (not a private method on {@code SubscriptionService}) specifically so its {@link
 * #write} can be {@code @Transactional}: {@code signUp} itself must not be, since it also
 * calls the payment-method gateway (an external call this project's rule forbids inside a
 * database transaction), and a private {@code @Transactional} method on the same bean
 * would be silently ignored by Spring's proxy-based AOP on a self-invoked call. By the
 * time {@link #write} runs, any required card attachment has already succeeded (or the
 * paid-signup path never reached here at all) -- this method only ever performs local
 * database writes.
 */
@Component
class SubscriptionSignupWriter {

    private final CustomerRepository customerRepository;
    private final PlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final AuditLogEntryRepository auditLogEntryRepository;
    private final CustomerTokenIssuer tokenIssuer;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Autowired
    SubscriptionSignupWriter(CustomerRepository customerRepository, PlanRepository planRepository,
                              SubscriptionRepository subscriptionRepository, AuditLogEntryRepository auditLogEntryRepository,
                              CustomerTokenIssuer tokenIssuer, PasswordEncoder passwordEncoder) {
        this(customerRepository, planRepository, subscriptionRepository, auditLogEntryRepository, tokenIssuer,
                passwordEncoder, Clock.systemUTC());
    }

    SubscriptionSignupWriter(CustomerRepository customerRepository, PlanRepository planRepository,
                              SubscriptionRepository subscriptionRepository, AuditLogEntryRepository auditLogEntryRepository,
                              CustomerTokenIssuer tokenIssuer, PasswordEncoder passwordEncoder, Clock clock) {
        this.customerRepository = customerRepository;
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.auditLogEntryRepository = auditLogEntryRepository;
        this.tokenIssuer = tokenIssuer;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * @param command      the signup request
     * @param customerId   the identity to persist the Customer under (existing, or a
     *                     freshly generated one for a new signup) -- resolved by {@link
     *                     SubscriptionService#signUp} before any card attachment, so it's
     *                     already the same id a just-attached PaymentMethod was saved
     *                     against
     * @param plan         the target Plan, already validated available
     * @param isFreePlan   whether {@code plan}'s current price is zero
     * @return the new Subscription's identity/state/Plan, a bearer token for its
     *         Customer, and whichever of {@code trialEndsAt}/{@code billingCycleAnchor}
     *         applies to the path taken
     * @throws DuplicateSubscriptionException if the identified Customer already has a
     *         non-{@code canceled} Subscription -- re-checked here (in addition to {@link
     *         SubscriptionService#signUp}'s earlier check) since this is the transaction
     *         that actually commits the new Subscription
     */
    @Transactional
    SubscriptionSignupResult write(SignupCommand command, UUID customerId, PlanSummary plan, boolean isFreePlan) {
        Customer customer = resolveCustomer(command, customerId);
        if (command.existingCustomerId() != null
                && subscriptionRepository.existsByCustomerIdAndStateNot(customer.getId(), SubscriptionState.CANCELED)) {
            throw new DuplicateSubscriptionException(customer.getId());
        }

        Plan planReference = planRepository.getReferenceById(plan.id());
        Subscription subscription;
        if (isFreePlan) {
            subscription = new Subscription(UUID.randomUUID(), customer, planReference, SubscriptionState.ACTIVE);
        } else {
            Instant now = Instant.now(clock);
            subscription = command.useTrial()
                    ? Subscription.startTrial(UUID.randomUUID(), customer, planReference, now.plus(SubscriptionService.TRIAL_DURATION))
                    : Subscription.startPaidImmediately(UUID.randomUUID(), customer, planReference, now);
        }
        subscriptionRepository.save(subscription);

        auditLogEntryRepository.append(new AuditLogEntry(
                UUID.randomUUID(), subscription.getId(), ActorType.CUSTOMER, null, subscription.getState().name(),
                command.correlationId()));

        String accessToken = tokenIssuer.issueFor(customer.getId());
        return new SubscriptionSignupResult(subscription.getId(), subscription.getState(), plan.id(), accessToken,
                subscription.getTrialEndsAt(), subscription.getBillingCycleAnchor());
    }

    private Customer resolveCustomer(SignupCommand command, UUID customerId) {
        if (command.existingCustomerId() != null) {
            return customerRepository.findById(command.existingCustomerId())
                    .orElseThrow(() -> new IllegalStateException(
                            "JWT identified Customer " + command.existingCustomerId() + " has no matching record"));
        }
        Customer customer = new Customer(customerId, command.email());
        customer.setPasswordHash(passwordEncoder.encode(command.password()));
        return customerRepository.save(customer);
    }
}
