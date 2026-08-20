# Daily batch billing job, not per-subscription scheduling

We need to trigger a charge on each Subscription's Anchor Date every month. We chose a single daily scheduled batch job that scans for subscriptions due (`due_date <= today`) and charges them, over having each Subscription schedule its own delayed trigger (e.g. a per-subscription Kafka delay or scheduler entry).

The batch job is simpler to reason about and test, and gets missed-run recovery for free: because the query is `<=` rather than `==`, a skipped day (deploy, outage) is caught up automatically by the next run with no separate backfill mechanism. The trade-off is coarser timing precision — a subscription's charge can land anywhere within the job's daily window rather than at a precise moment — which is acceptable since Billing Cycles are monthly.

Idempotency is enforced with a uniqueness constraint on `subscription_id + billing_period`, so a crash-and-restart or duplicate run of the job can never double-charge the same cycle.
