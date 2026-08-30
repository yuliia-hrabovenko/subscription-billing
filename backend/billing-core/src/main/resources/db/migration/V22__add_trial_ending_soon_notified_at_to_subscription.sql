-- Nullable: unset until the trial-ending-soon daily scan notifies this Subscription once;
-- the scan's own candidate query filters on this being null, so it never re-notifies the
-- same Subscription on a later run before its Trial converts or is canceled.
ALTER TABLE subscription
    ADD COLUMN trial_ending_soon_notified_at TIMESTAMPTZ;
