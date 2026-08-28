package com.subscriptionbilling.api.invoice;

import com.subscriptionbilling.invoicing.invoice.InvoicePage;

import java.util.List;

/** {@code GET /api/v1/subscriptions/{id}/invoices}'s response: one reverse-chronological page. */
public record InvoiceListResponse(List<InvoiceSummaryResponse> items, String nextCursor) {

    static InvoiceListResponse from(InvoicePage page) {
        return new InvoiceListResponse(page.items().stream().map(InvoiceSummaryResponse::from).toList(), page.nextCursor());
    }
}
