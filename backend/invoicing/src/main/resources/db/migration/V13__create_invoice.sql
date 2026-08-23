-- No FK to subscription/price_version: both are owned by billing-core, a different
-- module -- module boundary rule forbids database joins/constraints across logical
-- module boundaries. Referential integrity for those references is the writing module's
-- responsibility.
--
-- uq_invoice_subscription_billing_period is the mechanism that makes the billing job's
-- crash/retry/duplicate-run idempotency guarantee hold: it is what turns a repeated
-- insert attempt for the same cycle into a constraint violation instead of a second row.
CREATE TABLE invoice
(
    id               UUID         PRIMARY KEY,
    subscription_id  UUID         NOT NULL,
    billing_period   DATE         NOT NULL,
    price_version_id UUID         NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_invoice_subscription_billing_period UNIQUE (subscription_id, billing_period)
);
