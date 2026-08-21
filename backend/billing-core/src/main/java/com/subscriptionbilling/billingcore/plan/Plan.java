package com.subscriptionbilling.billingcore.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A named, priced billing offering. {@code retiredForSignup} hides a Plan from new
 * signups/plan-changes without affecting Subscriptions already on it.
 */
@Entity
@Table(name = "plan")
public class Plan {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "retired_for_signup", nullable = false)
    private boolean retiredForSignup;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Plan() {
    }

    public Plan(UUID id, String code, String name) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.retiredForSignup = false;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isRetiredForSignup() {
        return retiredForSignup;
    }

    public void retireForSignup() {
        this.retiredForSignup = true;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
