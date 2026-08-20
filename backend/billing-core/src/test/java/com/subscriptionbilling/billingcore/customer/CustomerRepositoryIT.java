package com.subscriptionbilling.billingcore.customer;

import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void savesAndFetchesACustomer() {
        Customer customer = new Customer(UUID.randomUUID(), "dana@example.com");

        customerRepository.saveAndFlush(customer);

        Optional<Customer> found = customerRepository.findById(customer.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("dana@example.com");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }
}
