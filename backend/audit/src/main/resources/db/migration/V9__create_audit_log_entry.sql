-- No FK to subscription (id): audit is a dependency of nearly every other module and must
-- not be coupled to any one caller's schema at the database level (module boundary rule —
-- no database joins/constraints across logical module boundaries). Referential integrity is
-- the writing module's responsibility, enforced at the application layer.
CREATE TABLE audit_log_entry
(
    id               UUID          PRIMARY KEY,
    subscription_id  UUID          NOT NULL,
    actor_type       VARCHAR(30)   NOT NULL,
    old_state        VARCHAR(30),
    new_state        VARCHAR(30)   NOT NULL,
    correlation_id   VARCHAR(100)  NOT NULL,
    occurred_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_entry_subscription_id ON audit_log_entry (subscription_id);
