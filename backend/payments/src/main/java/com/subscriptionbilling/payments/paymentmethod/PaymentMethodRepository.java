package com.subscriptionbilling.payments.paymentmethod;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, UUID> {

    Optional<PaymentMethod> findByCustomerId(UUID customerId);

    void deleteByCustomerId(UUID customerId);
}
