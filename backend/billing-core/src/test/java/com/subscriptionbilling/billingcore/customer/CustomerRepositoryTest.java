package com.subscriptionbilling.billingcore.customer;

import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class CustomerRepositoryTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void savesAndFetchesACustomer() {
        Customer customer = new Customer(UUID.randomUUID(), "customer@example.com");

        customerRepository.saveAndFlush(customer);

        Optional<Customer> found = customerRepository.findById(customer.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("customer@example.com");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }
}
