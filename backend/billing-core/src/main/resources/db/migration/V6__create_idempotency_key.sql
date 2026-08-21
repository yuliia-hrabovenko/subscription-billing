CREATE TABLE idempotency_key
(
    id               UUID PRIMARY KEY,
    customer_id      UUID         NOT NULL,
    operation        VARCHAR(100) NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_idempotency_key UNIQUE (customer_id, operation, idempotency_key)
);

CREATE INDEX idx_idempotency_key_expires_at ON idempotency_key (expires_at);
