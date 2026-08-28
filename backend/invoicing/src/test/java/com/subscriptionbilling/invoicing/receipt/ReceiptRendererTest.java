package com.subscriptionbilling.invoicing.receipt;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Narrow component-level test validating the rendered PDF's structural content: it must
 * contain the Plan name, the charged amount, the charge date, and the Invoice id --
 * self-sufficient evidence of a successful charge, extracted straight from the PDF text
 * rather than trusted from the inputs handed in.
 */
class ReceiptRendererTest {

    private final ReceiptRenderer receiptRenderer = new ReceiptRenderer();

    @Test
    void rendersAPdfWhoseTextContainsThePlanNameAmountChargeDateAndInvoiceId() throws IOException {
        UUID invoiceId = UUID.randomUUID();
        Instant chargedAt = Instant.parse("2026-08-24T03:00:00Z");

        byte[] pdf = receiptRenderer.render(invoiceId, "Enterprise", new BigDecimal("49.00"), chargedAt);

        String text;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(document);
        }
        assertThat(text).contains("Enterprise");
        assertThat(text).contains("49.00");
        assertThat(text).contains("2026-08-24");
        assertThat(text).contains(invoiceId.toString());
    }
}
