-- Optimistic-locking guard for cancel()/undoCancel(), the first Customer-triggered
-- mutations of an already-persisted Subscription.
ALTER TABLE subscription
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
