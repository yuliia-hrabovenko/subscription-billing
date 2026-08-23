package com.subscriptionbilling.invoicing.invoice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /** Backs the "one Invoice per cycle" find-or-create lookup a charge attempt needs before creating a new one. */
    Optional<Invoice> findBySubscriptionIdAndBillingPeriod(UUID subscriptionId, LocalDate billingPeriod);
}
