package com.subscriptionbilling.invoicing.receipt;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class ReceiptRenderer {

    private static final float PAGE_WIDTH = 612f;
    private static final float PAGE_HEIGHT = 792f;

    private static final float MARGIN = 60f;
    private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;

    private static final float HEADER_Y = PAGE_HEIGHT - 60f;
    private static final float FOOTER_Y = 35f;

    private static final float TITLE_FONT_SIZE = 24f;
    private static final float BODY_FONT_SIZE = 11f;
    private static final float SECTION_FONT_SIZE = 10f;
    private static final float FOOTER_FONT_SIZE = 8f;
    private static final float TOTAL_FONT_SIZE = 20f;

    private static final float STATUS_BOX_HEIGHT = 32f;
    private static final float ROW_SPACING = 25f;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMM dd, yyyy");

    private static final PDType1Font REGULAR =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private static final PDType1Font BOLD =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    /**
     * Renders a single-page receipt containing the facts necessary
     * to understand a successful charge without further lookup.
     *
     * @param invoiceId  invoice identifier
     * @param planName   charged plan name
     * @param amount     amount actually charged
     * @param chargedAt  instant when the successful payment was completed
     */
    public byte[] render(
            UUID invoiceId,
            String planName,
            BigDecimal amount,
            Instant chargedAt
    ) {
        LocalDate chargeDate = chargedAt
                .atZone(ZoneOffset.UTC)
                .toLocalDate();

        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            PDPage page = createPage(document);

            try (PDPageContentStream content = createContentStream(document, page)) {
                renderHeader(content);
                renderPaymentDetails(
                        content,
                        invoiceId,
                        planName,
                        chargeDate
                );
                renderTotal(content, amount);
                renderFooter(content);
            }

            document.save(output);
            return output.toByteArray();

        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to render receipt PDF for invoice " + invoiceId,
                    e
            );
        }
    }

    private PDPage createPage(PDDocument document) {
        PDPage page = new PDPage();
        document.addPage(page);
        return page;
    }

    private PDPageContentStream createContentStream(
            PDDocument document,
            PDPage page
    ) throws IOException {
        return new PDPageContentStream(document, page);
    }

    private void renderHeader(
            PDPageContentStream content
    ) throws IOException {

        float y = HEADER_Y;

        y = writeCenteredText(
                content,
                BOLD,
                TITLE_FONT_SIZE,
                "PAYMENT RECEIPT",
                y
        );

        y -= 22f;

        writeCenteredText(
                content,
                REGULAR,
                BODY_FONT_SIZE,
                "Thank you for your payment.",
                y
        );

        y -= 45f;

        drawStatusBox(content, y);

        writeText(
                content,
                BOLD,
                BODY_FONT_SIZE,
                "PAID",
                MARGIN + 12f,
                y + 10f
        );
    }

    private void renderPaymentDetails(
            PDPageContentStream content,
            UUID invoiceId,
            String planName,
            LocalDate chargeDate
    ) throws IOException {

        float y = 555f;

        y = writeSectionTitle(
                content,
                "PAYMENT DETAILS",
                y
        );

        y -= 18f;

        y = writeLabelValue(
                content,
                "Invoice",
                invoiceId.toString(),
                y
        );

        y = writeLabelValue(
                content,
                "Charge date",
                DATE_FORMAT.format(chargeDate),
                y
        );

        writeLabelValue(
                content,
                "Plan",
                planName,
                y
        );
    }

    private void renderTotal(
            PDPageContentStream content,
            BigDecimal amount
    ) throws IOException {

        float y = 425f;

        drawDivider(content, y);

        y -= 32f;

        writeText(
                content,
                REGULAR,
                SECTION_FONT_SIZE,
                "TOTAL CHARGED",
                MARGIN,
                y
        );

        y -= 28f;

        writeText(
                content,
                BOLD,
                TOTAL_FONT_SIZE,
                formatAmount(amount),
                MARGIN,
                y
        );

        drawDivider(content, y - 25f);
    }

    private void renderFooter(
            PDPageContentStream content
    ) throws IOException {

        writeText(
                content,
                REGULAR,
                FOOTER_FONT_SIZE,
                "This receipt confirms a successful payment.",
                MARGIN,
                105f
        );

        writeText(
                content,
                REGULAR,
                FOOTER_FONT_SIZE,
                "Please retain this receipt for your records.",
                MARGIN,
                91f
        );

        writeCenteredText(
                content,
                REGULAR,
                FOOTER_FONT_SIZE,
                "Receipt generated automatically",
                FOOTER_Y
        );
    }

    private float writeSectionTitle(
            PDPageContentStream content,
            String title,
            float y
    ) throws IOException {

        writeText(
                content,
                BOLD,
                SECTION_FONT_SIZE,
                title,
                MARGIN,
                y
        );

        drawDivider(content, y - 8f);

        return y - 8f;
    }

    private float writeLabelValue(
            PDPageContentStream content,
            String label,
            String value,
            float y
    ) throws IOException {

        writeText(
                content,
                REGULAR,
                BODY_FONT_SIZE,
                label,
                MARGIN,
                y
        );

        float valueWidth = textWidth(
                BOLD,
                BODY_FONT_SIZE,
                value
        );

        writeText(
                content,
                BOLD,
                BODY_FONT_SIZE,
                value,
                PAGE_WIDTH - MARGIN - valueWidth,
                y
        );

        return y - ROW_SPACING;
    }

    private float writeCenteredText(
            PDPageContentStream content,
            PDType1Font font,
            float fontSize,
            String text,
            float centerY
    ) throws IOException {

        float textWidth = textWidth(font, fontSize, text);
        float x = (PAGE_WIDTH - textWidth) / 2f;

        writeText(
                content,
                font,
                fontSize,
                text,
                x,
                centerY
        );

        return centerY;
    }

    private void writeText(
            PDPageContentStream content,
            PDType1Font font,
            float fontSize,
            String text,
            float x,
            float y
    ) throws IOException {

        content.beginText();
        content.setFont(font, fontSize);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    private float textWidth(
            PDType1Font font,
            float fontSize,
            String text
    ) throws IOException {

        return font.getStringWidth(text) / 1000f * fontSize;
    }

    private void drawDivider(
            PDPageContentStream content,
            float y
    ) throws IOException {

        content.moveTo(MARGIN, y);
        content.lineTo(PAGE_WIDTH - MARGIN, y);
        content.stroke();
    }

    private void drawStatusBox(
            PDPageContentStream content,
            float y
    ) throws IOException {

        content.addRect(
                MARGIN,
                y,
                CONTENT_WIDTH,
                STATUS_BOX_HEIGHT
        );

        content.stroke();
    }

    private String formatAmount(BigDecimal amount) {
        return "$" + amount.toPlainString();
    }
}