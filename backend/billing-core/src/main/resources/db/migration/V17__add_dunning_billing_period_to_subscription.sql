-- Nullable: while a Subscription is suspended mid-Dunning, due_date is repurposed to
-- hold the next scheduled retry date (see V12), so it can no longer identify which
-- Billing Cycle a retry Payment Attempt belongs to. This column preserves that original
-- billing period for the life of the Dunning cycle, so the billing job can keep
-- attaching every retry to the one Invoice already open for it (Invariant 6).
ALTER TABLE subscription
    ADD COLUMN dunning_billing_period DATE;
