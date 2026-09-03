-- No FK to customer: Customer is owned by billing-core, a different module -- module
-- boundary rule forbids database joins/constraints across logical module boundaries.
-- Referential integrity for customer_id is this module's own responsibility (see
-- invoice.subscription_id for the established precedent).
--
-- One row per Customer is an application-level invariant (PaymentMethodOnboardingService
-- replaces the existing row rather than inserting a second one), not a schema constraint --
-- this domain has exactly one card on file per Customer today, so there is nothing yet
-- that needs a uniqueness constraint to encode.
CREATE TABLE payment_method
(
    id                          UUID         PRIMARY KEY,
    customer_id                 UUID         NOT NULL,
    provider                    VARCHAR(20)  NOT NULL,
    provider_customer_id        VARCHAR(255) NOT NULL,
    provider_payment_method_id  VARCHAR(255) NOT NULL,
    type                        VARCHAR(20)  NOT NULL,
    brand                       VARCHAR(20),
    last4                       VARCHAR(4),
    expiry_month                INTEGER,
    expiry_year                 INTEGER,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_method_provider_ref UNIQUE (provider, provider_payment_method_id)
);
CREATE INDEX ix_payment_method_customer_id ON payment_method (customer_id);
