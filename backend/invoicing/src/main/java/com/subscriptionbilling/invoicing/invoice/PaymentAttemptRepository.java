package com.subscriptionbilling.invoicing.invoice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    /** Reconstructs an Invoice's charge-try history, ordered oldest attempt first. */
    @Query("SELECT p FROM PaymentAttempt p WHERE p.invoice.id = :invoiceId ORDER BY p.attemptedAt ASC")
    List<PaymentAttempt> findByInvoiceId(UUID invoiceId);

    /**
     * Finds the PaymentAttempt a payment-succeeded/failed webhook event's gateway
     * reference correlates to, if the synchronous charge path already recorded one.
     */
    Optional<PaymentAttempt> findFirstByGatewayReference(String gatewayReference);
}
