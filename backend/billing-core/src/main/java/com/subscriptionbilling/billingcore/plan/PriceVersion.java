package com.subscriptionbilling.billingcore.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One effective-dated price in a Plan's history. An Invoice always references the
 * PriceVersion in effect when it was charged, so a later price change never
 * retroactively alters a past Invoice (Invariant 9).
 */
@Entity
@Table(name = "price_version")
public class PriceVersion {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PriceVersion() {
    }

    public PriceVersion(UUID id, Plan plan, BigDecimal amount, Instant effectiveFrom) {
        this.id = id;
        this.plan = plan;
        this.amount = amount;
        this.effectiveFrom = effectiveFrom;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Plan getPlan() {
        return plan;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
