package com.subscriptionbilling.api.invoice;

import com.subscriptionbilling.invoicing.invoice.InvoiceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin visibility into any Subscription's Invoices/an Invoice by id — no
 * ownership check, unlike {@link InvoiceController}. Reuses that controller's
 * package-private {@link InvoiceResponse}/{@link InvoiceListResponse} DTOs, since the
 * wire shape is identical; only the service methods behind them differ.
 */
@RestController
public class AdminInvoiceController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final InvoiceService invoiceService;

    @Autowired
    public AdminInvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping("/api/v1/admin/subscriptions/{id}/invoices")
    public InvoiceListResponse list(@PathVariable UUID id, @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) String limit) {
        int pageSize = clamp(limit);
        return InvoiceListResponse.from(invoiceService.listForSubscriptionAsAdmin(id, cursor, pageSize));
    }

    @GetMapping("/api/v1/admin/invoices/{id}")
    public InvoiceResponse getOne(@PathVariable UUID id) {
        return InvoiceResponse.from(invoiceService.getByIdAsAdmin(id));
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
