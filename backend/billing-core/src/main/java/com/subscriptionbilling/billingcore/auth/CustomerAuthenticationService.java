package com.subscriptionbilling.billingcore.auth;

import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.subscription.Subscription;
import com.subscriptionbilling.billingcore.subscription.SubscriptionRepository;
import com.subscriptionbilling.billingcore.subscription.SubscriptionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.UUID;

/**
 * Validates {@code POST /api/v1/customers/login}'s credentials against the Customer's
 * stored {@link Customer#getPasswordHash()} (set at signup, see {@code
 * SubscriptionService#resolveCustomer}) and, on success, issues a bearer token via
 * {@link CustomerTokenIssuer} — the same token a signup response carries, so a logged-in
 * Customer is indistinguishable from a freshly-signed-up one to every other endpoint.
 */
@Service
public class CustomerAuthenticationService {

    /**
     * A fixed bcrypt hash with no real password behind it, compared against for an
     * unknown email so this method's runtime doesn't leak whether the email is
     * registered — this system's usual defense against username/email enumeration by
     * response-timing.
     */
    private static final String DUMMY_HASH_FOR_UNKNOWN_EMAIL =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5L1SLg74qMfuF3B/hh2A.hbAgvViG";

    private final CustomerRepository customerRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PasswordEncoder passwordEncoder;
    private final CustomerTokenIssuer tokenIssuer;

    @Autowired
    public CustomerAuthenticationService(CustomerRepository customerRepository,
                                          SubscriptionRepository subscriptionRepository,
                                          PasswordEncoder passwordEncoder, CustomerTokenIssuer tokenIssuer) {
        this.customerRepository = customerRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
    }

    /**
     * @param email    the submitted email
     * @param password the submitted password (raw, never logged)
     * @return a bearer token identifying the Customer, plus their current non-{@code
     *         canceled} Subscription's id if they have one (null otherwise — a Customer
     *         can still log in with no active Subscription, e.g. after their only one
     *         was canceled)
     * @throws CustomerAuthenticationException if the email is unknown, the Customer has
     *         no password set (e.g. a pre-login-feature row), or the password is wrong
     */
    public CustomerLoginResult authenticate(String email, String password) {
        Optional<Customer> customer = customerRepository.findByEmail(email);
        Optional<String> storedHash = customer.map(Customer::getPasswordHash).filter(StringUtils::hasText);
        // Always run the (expensive) hash comparison, even when no real Customer/hash
        // was found, so a caller can't distinguish "unknown email" / "no password set"
        // from "wrong password" by response timing.
        boolean passwordMatches = passwordEncoder.matches(
                password != null ? password : "", storedHash.orElse(DUMMY_HASH_FOR_UNKNOWN_EMAIL));
        if (storedHash.isEmpty() || !passwordMatches) {
            throw new CustomerAuthenticationException();
        }

        UUID customerId = customer.get().getId();
        String accessToken = tokenIssuer.issueFor(customerId);
        UUID subscriptionId = subscriptionRepository
                .findFirstByCustomerIdAndStateNot(customerId, SubscriptionState.CANCELED)
                .map(Subscription::getId)
                .orElse(null);
        return new CustomerLoginResult(accessToken, subscriptionId);
    }
}
