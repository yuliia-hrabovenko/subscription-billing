CREATE TABLE plan
(
    id                  UUID PRIMARY KEY,
    code                VARCHAR(100) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    retired_for_signup  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_plan_code UNIQUE (code)
);
