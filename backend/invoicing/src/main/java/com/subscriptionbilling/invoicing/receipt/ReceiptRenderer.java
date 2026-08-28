package com.subscriptionbilling.invoicing.receipt;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.UUID;

/**
 * Renders a receipt's fixed set of facts as a single-page PDF: Plan name, charged
 * amount, charge date, and Invoice id -- self-sufficient evidence of a successful
 * charge, with no further lookup needed to make sense of it.
 */
@Component
public class ReceiptRenderer {

    private static final DateTimeFormatter CHARGE_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final float LEFT_MARGIN = 60f;
    private static final float LINE_HEIGHT = 24f;
    private static final float START_Y = 720f;
    private static final float FONT_SIZE = 12f;

    /**
     * @param invoiceId the Invoice identifier to print
     * @param planName  the charged Plan's name
     * @param amount    the amount actually charged, from the Invoice's snapshotted
     *                  PriceVersion -- never the Plan's current price
     * @param chargedAt the instant the successful Payment Attempt resolved
     * @return the rendered PDF's bytes
     */
    public byte[] render(UUID invoiceId, String planName, BigDecimal amount, Instant chargedAt) {
        LocalDate chargeDate = chargedAt.atZone(ZoneOffset.UTC).toLocalDate();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                writeLine(contentStream, font, "Receipt", START_Y);
                writeLine(contentStream, font, "Invoice: " + invoiceId, START_Y - LINE_HEIGHT);
                writeLine(contentStream, font, "Plan: " + planName, START_Y - 2 * LINE_HEIGHT);
                writeLine(contentStream, font, "Amount charged: $" + amount.toPlainString(), START_Y - 3 * LINE_HEIGHT);
                writeLine(contentStream, font, "Charge date: " + CHARGE_DATE_FORMAT.format(chargeDate), START_Y - 4 * LINE_HEIGHT);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render receipt PDF for invoice " + invoiceId, e);
        }
    }

    private void writeLine(PDPageContentStream contentStream, PDType1Font font, String text, float y) throws IOException {
        contentStream.beginText();
        contentStream.setFont(font, FONT_SIZE);
        contentStream.newLineAtOffset(LEFT_MARGIN, y);
        contentStream.showText(text);
        contentStream.endText();
    }
}
