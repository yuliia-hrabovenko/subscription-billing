package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.audit.ActorType;
import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.auth.CustomerTokenIssuer;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.PlanCatalogService;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.plan.PlanSummary;
import com.subscriptionbilling.billingcore.plan.PlanUnavailableForSignupException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Owns the Subscription lifecycle's entry points: bringing a Subscription into
 * existence and fetching one back for its owner. Every write here is one atomic
 * use case — persistence, its audit trail, and (for signup) token issuance all commit
 * or fail together — not a sequence of independently-failable steps.
 */
@Service
public class SubscriptionService {

    private static final String SIGNUP_NEW_STATE = "ACTIVE";

    private final CustomerRepository customerRepository;
    private final PlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanCatalogService planCatalogService;
    private final AuditLogEntryRepository auditLogEntryRepository;
    private final CustomerTokenIssuer tokenIssuer;

    @Autowired
    public SubscriptionService(CustomerRepository customerRepository, PlanRepository planRepository,
                                SubscriptionRepository subscriptionRepository, PlanCatalogService planCatalogService,
                                AuditLogEntryRepository auditLogEntryRepository, CustomerTokenIssuer tokenIssuer) {
        this.customerRepository = customerRepository;
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planCatalogService = planCatalogService;
        this.auditLogEntryRepository = auditLogEntryRepository;
        this.tokenIssuer = tokenIssuer;
    }

    /**
     * The {@code [*] -> active} edge of the state diagram: a free Subscription starts
     * {@code active} with no prior state and no Billing Cycle (Invariant 5).
     */
    @Transactional
    public SubscriptionSignupResult signUpForFreePlan(FreeSignupCommand command) {
        PlanSummary plan = planCatalogService.findAvailablePlan(command.planId())
                .orElseThrow(() -> new PlanUnavailableForSignupException(command.planId()));
        // A zero-priced PriceVersion is what "the Free plan" means as a business rule -
        // checked by value, not by looking up a well-known Plan code, so
        // this stays correct even if the seeded Free plan's code ever changes.
        if (plan.currentPrice().signum() != 0) {
            throw new UnsupportedSignupPlanException(command.planId());
        }

        Customer customer = resolveCustomer(command);

        Subscription subscription = new Subscription(
                UUID.randomUUID(), customer, planRepository.getReferenceById(command.planId()),
                SubscriptionState.ACTIVE);
        subscriptionRepository.save(subscription);

        auditLogEntryRepository.append(new AuditLogEntry(
                UUID.randomUUID(), subscription.getId(), ActorType.CUSTOMER, null, SIGNUP_NEW_STATE,
                command.correlationId()));

        String accessToken = tokenIssuer.issueFor(customer.getId());
        return new SubscriptionSignupResult(subscription.getId(), subscription.getState(), plan.id(), accessToken);
    }

    /**
     * Fetches a Subscription for the Customer identified by their own bearer token.
     * Not-found and not-owned collapse into the same {@link SubscriptionAccessDeniedException}
     * — see its Javadoc for why that's the ownership check's whole point, not an
     * oversight.
     */
    @Transactional(readOnly = true)
    public SubscriptionView getOwnSubscription(UUID subscriptionId, UUID authenticatedCustomerId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .filter(candidate -> candidate.getCustomer().getId().equals(authenticatedCustomerId))
                .orElseThrow(() -> new SubscriptionAccessDeniedException(subscriptionId));
        return SubscriptionView.from(subscription);
    }

    private Customer resolveCustomer(FreeSignupCommand command) {
        if (command.existingCustomerId() != null) {
            // A bearer token was presented at signup: ADR-0003's identity-bootstrap
            // exception for a re-subscribing Customer, so this Subscription attaches to
            // the Customer the token already identifies rather than minting a second
            // Customer record. A token we issued pointing at no Customer record would be
            // a system consistency bug, not a client error — left to the generic 500
            // handler rather than modeled as a client-facing exception.
            return customerRepository.findById(command.existingCustomerId())
                    .orElseThrow(() -> new IllegalStateException(
                            "JWT identified Customer " + command.existingCustomerId() + " has no matching record"));
        }
        return customerRepository.save(new Customer(UUID.randomUUID(), command.email()));
    }
}
