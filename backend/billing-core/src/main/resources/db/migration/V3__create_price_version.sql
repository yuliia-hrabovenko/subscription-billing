CREATE TABLE price_version
(
    id              UUID PRIMARY KEY,
    plan_id         UUID           NOT NULL REFERENCES plan (id),
    amount          NUMERIC(19, 4) NOT NULL,
    effective_from  TIMESTAMPTZ    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_price_version_plan_id ON price_version (plan_id);
