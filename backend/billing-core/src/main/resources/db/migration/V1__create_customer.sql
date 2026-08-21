CREATE TABLE customer
(
    id                    UUID PRIMARY KEY,
    email                 VARCHAR(255) NOT NULL,
    payment_method_token  VARCHAR(255),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_email UNIQUE (email)
);
