package com.subscriptionbilling.billingjob.invoicing;

/**
 * Thrown by a {@link ChargeRecordingPort} implementation when persisting this charge
 * attempt's Invoice loses a race to another job instance that committed one first for the
 * same {@code (subscriptionId, billingPeriod)} — the database's uniqueness constraint is
 * the final word, caught here so the losing instance doesn't crash or record a duplicate.
 * Not a charge failure: the gateway attempt that preceded this already happened and is
 * not retried; the winning instance's own recording is this cycle's outcome of record.
 */
public class ChargeAlreadyRecordedException extends RuntimeException {

    public ChargeAlreadyRecordedException(String message, Throwable cause) {
        super(message, cause);
    }
}
