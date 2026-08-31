package com.subscriptionbilling.billingcore.customer;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /**
     * Backs the Admin customer list endpoint's reverse-chronological cursor pagination
     * — same {@code (createdAt, id)} order-and-filter shape as invoicing's
     * {@code InvoiceRepository.findPageBySubscriptionId}, and the same reason for the
     * explicit {@code CAST(... AS java.time.Instant)}: Postgres can't otherwise infer a
     * type for a parameter used only in an {@code IS NULL} comparison. Pass null for
     * {@code cursorCreatedAt}/{@code cursorId} to fetch the first page; {@code pageable}
     * should request one row beyond the caller's page size so the caller can tell
     * whether a further page exists.
     */
    @Query("SELECT c FROM Customer c WHERE "
            + "(CAST(:cursorCreatedAt AS java.time.Instant) IS NULL "
            + "     OR c.createdAt < :cursorCreatedAt "
            + "     OR (c.createdAt = :cursorCreatedAt AND c.id < :cursorId)) "
            + "ORDER BY c.createdAt DESC, c.id DESC")
    List<Customer> findPage(@Param("cursorCreatedAt") Instant cursorCreatedAt, @Param("cursorId") UUID cursorId,
                             Pageable pageable);
}
