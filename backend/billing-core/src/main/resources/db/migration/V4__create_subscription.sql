CREATE TABLE subscription
(
    id               UUID PRIMARY KEY,
    customer_id      UUID         NOT NULL REFERENCES customer (id),
    plan_id          UUID         NOT NULL REFERENCES plan (id),
    pending_plan_id  UUID REFERENCES plan (id),
    state            VARCHAR(30)  NOT NULL,
    trial_used       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_subscription_customer_id ON subscription (customer_id);

-- Invariant 1: a Customer has at most one non-canceled Subscription at a time.
CREATE UNIQUE INDEX uq_subscription_one_active_per_customer
    ON subscription (customer_id)
    WHERE state <> 'CANCELED';
