package com.subscriptionbilling.invoicing.invoice;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /** Backs the "one Invoice per cycle" find-or-create lookup a charge attempt needs before creating a new one. */
    Optional<Invoice> findBySubscriptionIdAndBillingPeriod(UUID subscriptionId, LocalDate billingPeriod);

    /** Backs the billing job's pre-charge idempotency check: skip a cycle already invoiced by a prior run. */
    boolean existsBySubscriptionIdAndBillingPeriod(UUID subscriptionId, LocalDate billingPeriod);

    /**
     * Backs the list endpoint's reverse-chronological cursor pagination: {@code
     * (createdAt, id)} both order and filter this query, so a tie on {@code createdAt}
     * (possible under a coarse clock) can never cause a row to be skipped or repeated
     * across pages. Pass null for {@code cursorCreatedAt}/{@code cursorId} to fetch the
     * first page; {@code pageable} should request one row beyond the caller's page size
     * so the caller can tell whether a further page exists.
     */
    @Query("SELECT i FROM Invoice i WHERE i.subscriptionId = :subscriptionId "
            + "AND (:cursorCreatedAt IS NULL "
            + "     OR i.createdAt < :cursorCreatedAt "
            + "     OR (i.createdAt = :cursorCreatedAt AND i.id < :cursorId)) "
            + "ORDER BY i.createdAt DESC, i.id DESC")
    List<Invoice> findPageBySubscriptionId(@Param("subscriptionId") UUID subscriptionId,
                                            @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                            @Param("cursorId") UUID cursorId,
                                            Pageable pageable);
}
