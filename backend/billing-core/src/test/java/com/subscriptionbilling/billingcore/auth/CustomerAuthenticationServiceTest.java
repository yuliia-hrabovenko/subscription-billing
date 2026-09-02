package com.subscriptionbilling.billingcore.auth;

import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.subscription.Subscription;
import com.subscriptionbilling.billingcore.subscription.SubscriptionRepository;
import com.subscriptionbilling.billingcore.subscription.SubscriptionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerAuthenticationServiceTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private CustomerTokenIssuer tokenIssuer;

    private final UUID customerId = UUID.randomUUID();
    private final Plan freePlan = new Plan(UUID.randomUUID(), "free", "Free");

    @Test
    void correctCredentialsAgainstAStoredHashIssueATokenAndTheCurrentSubscriptionId() {
        Customer customer = customerWithPassword("s3cret!");
        when(customerRepository.findByEmail("returning@example.com")).thenReturn(Optional.of(customer));
        when(tokenIssuer.issueFor(customerId)).thenReturn("minted-token");
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(subscriptionId, customer, freePlan, SubscriptionState.ACTIVE);
        when(subscriptionRepository.findFirstByCustomerIdAndStateNot(customerId, SubscriptionState.CANCELED))
                .thenReturn(Optional.of(subscription));

        CustomerLoginResult result = service().authenticate("returning@example.com", "s3cret!");

        assertThat(result.accessToken()).isEqualTo("minted-token");
        assertThat(result.subscriptionId()).isEqualTo(subscriptionId);
    }

    @Test
    void aCustomerWithNoNonCanceledSubscriptionStillLogsInWithANullSubscriptionId() {
        Customer customer = customerWithPassword("s3cret!");
        when(customerRepository.findByEmail("returning@example.com")).thenReturn(Optional.of(customer));
        when(tokenIssuer.issueFor(customerId)).thenReturn("minted-token");
        when(subscriptionRepository.findFirstByCustomerIdAndStateNot(customerId, SubscriptionState.CANCELED))
                .thenReturn(Optional.empty());

        CustomerLoginResult result = service().authenticate("returning@example.com", "s3cret!");

        assertThat(result.accessToken()).isEqualTo("minted-token");
        assertThat(result.subscriptionId()).isNull();
    }

    @Test
    void wrongPasswordAgainstAStoredHashIsRejected() {
        Customer customer = customerWithPassword("s3cret!");
        when(customerRepository.findByEmail("returning@example.com")).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service().authenticate("returning@example.com", "wrong-password"))
                .isInstanceOf(CustomerAuthenticationException.class);
    }

    @Test
    void unknownEmailIsRejectedWithTheSameExceptionAsAWrongPassword() {
        when(customerRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().authenticate("nobody@example.com", "s3cret!"))
                .isInstanceOf(CustomerAuthenticationException.class);
    }

    @Test
    void aCustomerWithNoPasswordSetIsRejected() {
        Customer customer = new Customer(customerId, "no-password@example.com");
        when(customerRepository.findByEmail("no-password@example.com")).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service().authenticate("no-password@example.com", "anything"))
                .isInstanceOf(CustomerAuthenticationException.class);
    }

    private Customer customerWithPassword(String rawPassword) {
        Customer customer = new Customer(customerId, "returning@example.com");
        customer.setPasswordHash(ENCODER.encode(rawPassword));
        return customer;
    }

    private CustomerAuthenticationService service() {
        return new CustomerAuthenticationService(customerRepository, subscriptionRepository, ENCODER, tokenIssuer);
    }
}
