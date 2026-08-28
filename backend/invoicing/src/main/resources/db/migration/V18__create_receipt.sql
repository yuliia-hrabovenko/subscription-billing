-- One receipt PDF per Invoice, stored as a blob so the download endpoint can serve it
-- without re-rendering on every request. FK to invoice is fine -- Invoice is owned by
-- this same module, unlike subscription_id/price_version_id on invoice itself.
CREATE TABLE receipt
(
    id         UUID        PRIMARY KEY,
    invoice_id UUID        NOT NULL REFERENCES invoice (id),
    pdf        BYTEA       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_receipt_invoice_id UNIQUE (invoice_id)
);
