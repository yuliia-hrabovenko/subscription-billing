CREATE TABLE webhook_event
(
    id               UUID PRIMARY KEY,
    gateway_event_id VARCHAR(255) NOT NULL,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_webhook_event_gateway_event_id UNIQUE (gateway_event_id)
);
