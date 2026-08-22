-- Paid-plan signup (trial and immediate-paid) needs somewhere to record when a Trial
-- ends and when a Billing Cycle first anchors; neither existed before only free-plan
-- signup was implemented.
ALTER TABLE subscription
    ADD COLUMN trial_ends_at        TIMESTAMPTZ,
    ADD COLUMN billing_cycle_anchor TIMESTAMPTZ;
