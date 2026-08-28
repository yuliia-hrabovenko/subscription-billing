package com.subscriptionbilling.invoicing.receipt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.subscriptionbilling.invoicing.invoice.Invoice;

import java.time.Instant;
import java.util.UUID;

/**
 * The rendered PDF receipt for one Invoice, stored so it can be served on download
 * without re-rendering. Exactly one per Invoice, enforced by a database unique
 * constraint on {@code invoice_id}.
 */
@Entity
@Table(name = "receipt", uniqueConstraints = @UniqueConstraint(name = "uq_receipt_invoice_id", columnNames = "invoice_id"))
public class Receipt {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Lob
    @Column(nullable = false)
    private byte[] pdf;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Receipt() {
    }

    /**
     * @param id      the Receipt's identity
     * @param invoice the Invoice this receipt was rendered for
     * @param pdf     the rendered PDF's bytes
     */
    public Receipt(UUID id, Invoice invoice, byte[] pdf) {
        this.id = id;
        this.invoice = invoice;
        this.pdf = pdf;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public byte[] getPdf() {
        return pdf;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
