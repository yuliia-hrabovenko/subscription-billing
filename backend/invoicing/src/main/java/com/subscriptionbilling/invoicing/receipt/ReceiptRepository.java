package com.subscriptionbilling.invoicing.receipt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    /** Backs the download endpoint's fetch-by-Invoice lookup. */
    Optional<Receipt> findByInvoiceId(UUID invoiceId);
}
