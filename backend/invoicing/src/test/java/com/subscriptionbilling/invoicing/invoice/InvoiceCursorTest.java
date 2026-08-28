package com.subscriptionbilling.invoicing.invoice;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage of {@link InvoiceCursor}: a round trip must reproduce the exact {@code
 * (createdAt, id)} it was built from (the pair the list query filters against), null/blank
 * decodes to "first page", and anything else not shaped like a cursor this API issued is
 * rejected rather than silently misread.
 */
class InvoiceCursorTest {

    @Test
    void decodingNullOrBlankMeansFirstPage() {
        assertThat(InvoiceCursor.decode(null)).isNull();
        assertThat(InvoiceCursor.decode("")).isNull();
        assertThat(InvoiceCursor.decode("   ")).isNull();
    }

    @Test
    void encodingThenDecodingReproducesTheExactCreatedAtAndId() {
        Instant createdAt = Instant.parse("2026-08-24T03:15:42.123456Z");
        UUID id = UUID.randomUUID();

        String cursor = InvoiceCursor.encode(createdAt, id);
        InvoiceCursor.Position decoded = InvoiceCursor.decode(cursor);

        assertThat(decoded.createdAt()).isEqualTo(createdAt);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void aCursorThisApiNeverIssuedIsRejected() {
        assertThatThrownBy(() -> InvoiceCursor.decode("not-a-real-cursor"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void aCursorMissingTheIdSeparatorIsRejected() {
        String malformed = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-08-24T03:15:42Z-no-separator".getBytes());

        assertThatThrownBy(() -> InvoiceCursor.decode(malformed))
                .isInstanceOf(InvalidCursorException.class);
    }
}
