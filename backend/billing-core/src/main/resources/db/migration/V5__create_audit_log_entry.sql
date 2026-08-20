CREATE TABLE audit_log_entry
(
    id               UUID PRIMARY KEY,
    subscription_id  UUID         NOT NULL REFERENCES subscription (id),
    actor            VARCHAR(100) NOT NULL,
    event_type       VARCHAR(100) NOT NULL,
    old_value        VARCHAR(100),
    new_value        VARCHAR(100),
    occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_entry_subscription_id ON audit_log_entry (subscription_id);
