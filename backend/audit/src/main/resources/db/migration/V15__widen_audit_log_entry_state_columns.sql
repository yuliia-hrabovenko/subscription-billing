-- old_state/new_state are documented as plain strings, not restricted to any one
-- caller's enum (see AuditLogEntry's Javadoc) -- 30 chars fits every SubscriptionState
-- name but not an arbitrary Plan code (e.g. a pending-plan-change application writes
-- Plan.code, not a SubscriptionState). Widened to match correlation_id's headroom.
ALTER TABLE audit_log_entry ALTER COLUMN old_state TYPE VARCHAR(100);
ALTER TABLE audit_log_entry ALTER COLUMN new_state TYPE VARCHAR(100);
