-- invoice_id REFERENCES invoice(id): Invoice is owned by this same module, so unlike
-- invoice's own subscription_id/price_version_id columns, this FK does not cross a
-- module boundary.
CREATE TABLE payment_attempt
(
    id           UUID         PRIMARY KEY,
    invoice_id   UUID         NOT NULL REFERENCES invoice (id),
    status       VARCHAR(20)  NOT NULL,
    attempted_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_payment_attempt_invoice_id ON payment_attempt (invoice_id);
