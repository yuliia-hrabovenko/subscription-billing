package com.subscriptionbilling.billingjob.invoicing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The seam a resolved charge attempt is recorded through, so the billing job drives
 * Invoice/PaymentAttempt creation without owning the Invoicing module's data model. An
 * implementation owns the "one Invoice per (subscription, billing period)" rule and its
 * enforcement: a caller that loses a create-Invoice race to another job instance sees
 * {@link ChargeAlreadyRecordedException} rather than a duplicate row or a raw persistence
 * exception.
 */
public interface ChargeRecordingPort {

    /**
     * Reports whether this Billing Cycle already has an outcome recorded, so the billing
     * job can skip a charge attempt entirely rather than call the gateway again — the
     * common case of a job re-run after a prior run's own success was never observed
     * (crash, timeout, ...).
     *
     * @param subscriptionId the Subscription to check
     * @param billingPeriod  the Billing Cycle date to check
     * @return true if an Invoice already exists for {@code (subscriptionId, billingPeriod)}
     */
    boolean invoiceAlreadyRecorded(UUID subscriptionId, LocalDate billingPeriod);

    /**
     * Records a successful charge: creates (or attaches to the existing) Invoice for
     * {@code (subscriptionId, billingPeriod)} and appends a succeeded PaymentAttempt to
     * it.
     *
     * @param subscriptionId       the Subscription charged
     * @param billingPeriod        the Billing Cycle date charged for
     * @param priceVersionId       the PriceVersion charged, snapshotted onto the Invoice
     * @param gatewayTransactionId the gateway's reference for the completed charge
     * @param attemptedAt          the instant the gateway resolved the charge
     * @throws ChargeAlreadyRecordedException if creating the Invoice lost a race to
     *         another job instance recording this same cycle concurrently
     */
    void recordSuccessfulCharge(UUID subscriptionId, LocalDate billingPeriod, UUID priceVersionId,
                                 String gatewayTransactionId, Instant attemptedAt);

    /**
     * Records a failed charge attempt: creates (or attaches to the existing) Invoice for
     * {@code (subscriptionId, billingPeriod)} — a first attempt failing still creates the
     * Invoice, per the "create on first attempt" rule — and appends a failed
     * PaymentAttempt to it.
     *
     * @param subscriptionId the Subscription charged
     * @param billingPeriod  the Billing Cycle date charged for
     * @param priceVersionId the PriceVersion charged, snapshotted onto the Invoice
     * @param attemptedAt    the instant the gateway resolved the charge
     * @return the id of the Invoice the failed attempt was recorded against, for the
     *         Dunning hand-off's correlation id
     * @throws ChargeAlreadyRecordedException if creating the Invoice lost a race to
     *         another job instance recording this same cycle concurrently
     */
    UUID recordFailedCharge(UUID subscriptionId, LocalDate billingPeriod, UUID priceVersionId, Instant attemptedAt);
}
