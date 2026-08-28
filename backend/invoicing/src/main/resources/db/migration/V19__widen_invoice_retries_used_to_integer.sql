-- The entity maps retriesUsed as a Java int, which Hibernate expects as SQL INTEGER;
-- V16 created this column as SMALLINT, which schema validation (ddl-auto=validate)
-- rejects as a type mismatch.
ALTER TABLE invoice
    ALTER COLUMN retries_used TYPE INTEGER;
