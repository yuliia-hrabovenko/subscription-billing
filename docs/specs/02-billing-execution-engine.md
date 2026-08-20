# Spec: Billing Execution Engine (Daily Batch Job)

Source: `docs/product/product-prd.md` §7.2, §12 Phase 2. ADR: `docs/adr/0001-daily-batch-billing-job.md`. Module: `billing-job`.

## Problem Statement

Every paid Subscription must be charged automatically on its Anchor Date, exactly once per Billing Cycle, with no manual trigger and no human review of the common path. The system must survive a crashed run, a duplicate run, or a missed day (deploy, outage) without ever double-charging a Customer or requiring a separate backfill process.

## Solution

A daily scheduled batch job (`billing-job` module), fixed at an off-peak run time (~03:00 UTC per ADR-0001), scans for Subscriptions with `due_date <= today` (not `== today`, so a skipped run is caught up automatically by the next one), invokes a Payment Attempt per due Subscription through `PaymentGatewayClient`, and advances the Anchor Date on success. Idempotency is enforced by a database uniqueness constraint on `(subscription_id, billing_period)` — the same guarantee that makes a crash-and-restart or duplicate execution safe, per ADR-0001 and Invariant 6.

## User Stories

1. As the system, I want a daily job that runs at a fixed off-peak time, so that billing load doesn't compete with customer-facing traffic.
2. As the system, I want the job to find every Subscription with `due_date <= today`, so that a missed run (deploy, outage) is caught up automatically on the next run with no separate backfill mechanism.
3. As the system, I want each due Subscription charged through `PaymentGatewayClient`, so that the billing job never talks to the gateway's API shape directly.
4. As the system, I want a successful charge to create exactly one Invoice (or attach to the existing one for that cycle) and advance the Anchor Date, so that the next Billing Cycle is scheduled correctly.
5. As the system, I want Anchor Date advancement to apply Stripe-style month-end clamping (Jan 31 → Feb 28/29 → Mar 31...), so that short months never produce an invalid billing date.
6. As the system, I want a failed charge to leave the Subscription's Anchor Date unchanged and hand off to Dunning/suspension logic, so that a declined card doesn't silently advance the billing schedule.
7. As the system, I want the `(subscription_id, billing_period)` uniqueness constraint enforced at the database level, so that a crashed-and-restarted job run, or two overlapping job instances, can never charge the same Subscription twice for the same cycle.
8. As the system, I want a Trial's auto-conversion charge processed through the same code path as an ordinary renewal, so that there's no special-cased "first charge" logic to maintain separately.
9. As an on-call engineer, I want Prometheus metrics for job duration, subscriptions processed, and failures, so that I can tell the job ran and ran correctly without reading raw logs.
10. As an on-call engineer, I want the job deployed as a Kubernetes CronJob (or equivalent single-execution-per-day infra primitive), so that infra-level single-execution semantics back up the DB-level idempotency constraint as defense in depth.
11. As the system, I want a transient gateway-side failure (timeout, 5xx) handled by the Resilience4j-wrapped client's bounded retry/circuit breaker, distinct from a business-level decline, so that an infra blip doesn't get treated as (or trigger) a Dunning cycle.
12. As a future maintainer, I want an integration test that runs the job twice against the same due Subscription, so that "duplicate run cannot double-charge" is proven, not assumed.

## Implementation Decisions

- **Trigger**: a `BillingJobRunner` (or equivalently named) component with a single public, directly-invokable entry point — not only a `@Scheduled` annotation — so it can be triggered by a Kubernetes CronJob, a manual operator run, and a test, through the same code path.
- **Query**: `due_date <= today` against `Subscription`, per ADR-0001 — never `== today`.
- **Idempotency**: unique constraint on `(subscription_id, billing_period)`, owned at the database/migration level (Flyway), enforced regardless of which code path attempts the write.
- **Anchor Date**: the `AnchorDate` value object owns month-end clamping logic; the billing job calls into it rather than reimplementing date math inline.
- **Collaboration**: the job creates/attaches Invoices and Payment Attempts via the Invoicing module's interface, and hands off failed charges to the Dunning module's suspension logic — this module owns *scheduling and driving* the charge attempt, not the Invoice/Dunning data model itself.
- **Resilience**: gateway calls go through `PaymentGatewayClient`, which is itself Resilience4j-wrapped (see Payment Gateway Integration spec) — this module does not implement its own retry/circuit-breaker logic around the gateway call.
- **Deployment**: Kubernetes CronJob at the fixed off-peak time, per PRD §9's defense-in-depth guidance.
- **Module boundary**: `billing-job`, per PRD §6.

## Testing Decisions

- **Seam**: the job runner's public trigger method, invoked directly (not via HTTP — this is not a customer-facing operation), backed by Testcontainers Postgres and the WireMock-stubbed gateway.
- A good test here drives the job against seeded Subscription/Invoice state and asserts on post-run database state (Invoice created, Anchor Date advanced, Subscription state) and on Prometheus counters — never on internal method call counts.
- Required integration tests: (a) running the job twice against the same due Subscription produces exactly one Invoice/Payment Attempt for that billing period, proving the duplicate-run guarantee; (b) a Subscription with `due_date` several days in the past (simulating a missed run) is caught up correctly; (c) a full Trial → `active` conversion charge succeeds through the same path as an ordinary renewal.
- Prior art: none yet in this docs-only repo; this spec and Dunning & Recovery share the same seam and should share test fixtures/setup where practical.

## Out of Scope

- Dunning retry *scheduling* logic itself (day 1/3/7 offsets, suspension/recovery rules) — see Dunning & Recovery spec; this module only drives the initial charge and hands off failures.
- Webhook-driven state changes (payment confirmation arriving async via webhook rather than a synchronous gateway response) — see Webhook Ingestion spec.
- Invoice PDF rendering — see Invoicing & Receipts spec.
- The `PaymentGatewayClient` adapter implementation itself — see Payment Gateway Integration spec.

## Further Notes

- ADR-0001 is authoritative for the batch-vs-per-subscription-scheduling decision and the idempotency mechanism; do not revisit that trade-off within this spec.
- Risk R4 (coarse billing-job timing precision — a charge can land anywhere in the daily window) is an accepted trade-off; customer-facing copy about "charges process within 24 hours of your billing date" is a frontend concern, not this module's.
