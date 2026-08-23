-- Nullable: identifies which Subscriptions are due for a charge (compared against
-- today's date). A later ticket sets it for the first time from the signup paths, and
-- decides how the due-Subscription scan is exposed across the module boundary -- this
-- migration only adds the column.
ALTER TABLE subscription
    ADD COLUMN due_date DATE;

CREATE INDEX idx_subscription_due_date ON subscription (due_date) WHERE due_date IS NOT NULL;
