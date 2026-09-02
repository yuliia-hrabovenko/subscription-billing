package com.subscriptionbilling.invoicing.receipt;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
            Files.write(Paths.get("test.pdf"), pdf);
        }
        assertThat(text).contains("Enterprise");
        assertThat(text).contains("49.00");
        assertThat(text).contains("Aug 24, 2026");
        assertThat(text).contains(invoiceId.toString());
    }
}
