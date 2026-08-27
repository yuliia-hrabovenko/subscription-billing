-- Bookkeeping for Dunning: counts retry Payment Attempts (scheduled or self-service)
-- against this Invoice, capped at 3 by the entity (Invariant 11). Owned here, not in
-- dunning, because Invoice's persistence model belongs to this module.
ALTER TABLE invoice
    ADD COLUMN retries_used SMALLINT NOT NULL DEFAULT 0;
