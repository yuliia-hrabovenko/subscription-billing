package com.subscriptionbilling.api.invoice;

import com.subscriptionbilling.invoicing.invoice.InvoiceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A subscriber's own billing history: listing a Subscription's Invoices and fetching
 * one individually with its PaymentAttempt history. Both endpoints are bearer +
 * ownership-checked against the target's owning Subscription (ADR-0003); {@link
 * InvoiceService} is what turns a rejection into {@code 403}, never {@code 404} — see
 * its Javadoc.
 */
@RestController
public class InvoiceController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final InvoiceService invoiceService;

    @Autowired
    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping("/api/v1/subscriptions/{id}/invoices")
    public InvoiceListResponse list(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) String limit) {
        UUID authenticatedCustomerId = UUID.fromString(jwt.getSubject());
        int pageSize = clamp(limit);
        return InvoiceListResponse.from(invoiceService.listForSubscription(id, authenticatedCustomerId, cursor, pageSize));
    }

    @GetMapping("/api/v1/invoices/{id}")
    public InvoiceResponse getOne(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID authenticatedCustomerId = UUID.fromString(jwt.getSubject());
        return InvoiceResponse.from(invoiceService.getById(id, authenticatedCustomerId));
    }

    /** Falls back to {@link #DEFAULT_PAGE_SIZE} for anything blank, non-numeric, or below 1, rather than rejecting the request. */
    private static int clamp(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_PAGE_SIZE;
        }
        try {
            int parsed = Integer.parseInt(requested.trim());
            return parsed < 1 ? DEFAULT_PAGE_SIZE : Math.min(parsed, MAX_PAGE_SIZE);
        } catch (NumberFormatException notANumber) {
            return DEFAULT_PAGE_SIZE;
        }
    }
}
