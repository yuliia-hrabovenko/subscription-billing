-- Nullable: a PaymentAttempt is only ever recorded once the gateway has resolved a
-- charge, but not every gateway response carries a reference for a declined charge, and
-- a transiently-failed attempt is never recorded at all (see ChargeRecordingPort).
ALTER TABLE payment_attempt ADD COLUMN gateway_reference VARCHAR(255);

-- The correlation key a payment-succeeded/failed webhook event uses to find the specific
-- PaymentAttempt it's reporting -- partial index since most lookups are
-- webhook-driven and a reference is only sometimes present.
CREATE INDEX idx_payment_attempt_gateway_reference ON payment_attempt (gateway_reference)
    WHERE gateway_reference IS NOT NULL;
