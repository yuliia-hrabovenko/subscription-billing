# Spec: Dunning & Recovery

Source: `docs/product/product-prd.md` §7.4, §12 Phase 3. Module: `dunning`.

## Problem Statement

When a renewal or Trial-conversion charge fails, the system must protect against further paid access immediately while still giving the Customer a bounded, automatic path back to `active` — without any admin/support role available to intervene, and without the retry process ever running indefinitely.

## Solution

The `dunning` module implements the `DunningSchedule` value object (retries at day 1, day 3, day 7 after the initial failure), transitions a Subscription to `suspended` immediately on the first failed charge (no grace period), reverts it to `active` on any successful Payment Attempt — scheduled or customer-triggered — and cancels it if the day-7 (3rd) retry also fails. A self-service retry endpoint lets a `suspended` Customer trigger an extra attempt at any time after updating their card, without resetting or extending the schedule.

## User Stories

1. As the system, I want the first failed renewal charge to suspend the Subscription immediately, so that there's no ambiguity about what a "still trying" Customer can access.
2. As the system, I want scheduled retries fired on day 1, day 3, and day 7 after the initial failure, so that recovery is attempted automatically without any manual trigger.
3. As a suspended subscriber, I want to trigger an immediate retry myself after updating my card, so that I don't have to wait for the next scheduled Dunning attempt to regain access.
4. As a suspended subscriber, I want a successful self-service retry to restore me to `active` immediately, so that fixing my card gets me back in without delay.
5. As the system, I want a self-service retry to not reset or extend the day 1/3/7 schedule, so that a Customer who retries and fails again isn't granted extra attempts beyond the bounded total.
6. As the system, I want every Payment Attempt — scheduled or self-triggered — to attach to the same Invoice for that Billing Cycle, so that Invariant 6 (one Invoice per cycle) holds regardless of how the retry was triggered.
7. As the system, I want the day-7 (3rd) scheduled retry's failure to cancel the Subscription, so that Dunning is provably bounded at 3 retries (Invariant 11) and never loops indefinitely.
8. As a subscriber who cancels while `suspended`, I want the cancellation to be immediate, so that there's no pending-cancellation period protecting access I no longer have.
9. As an on-call engineer, I want Prometheus metrics for Dunning counts (attempts, recoveries, exhaustions), so that I can see revenue-recovery health without reading raw logs.
10. As a future maintainer, I want the self-service retry endpoint to require the caller to own the target Subscription, so that Dunning doesn't reopen an admin-override path by accident.
11. As a client with a flaky connection, I want the self-service retry endpoint to accept an `Idempotency-Key`, so that a retried request can't trigger two simultaneous Payment Attempts.

## Implementation Decisions

- **Schedule**: `DunningSchedule` computed relative to the Invoice's initial (failed) Payment Attempt timestamp — day 1, day 3, day 7 offsets, fixed per Business Rule in `domain-model.md`.
- **Scheduled retries**: driven by the same daily billing-job run (see Billing Execution Engine) — the job's due-query includes both renewal-due Subscriptions and Subscriptions with a due Dunning retry, both charged through `PaymentGatewayClient`.
- **Self-service retry**: `POST /api/v1/subscriptions/{id}/retry-payment`, JWT-authenticated with the standard ownership check, guarded by an `Idempotency-Key` header, charges synchronously via `PaymentGatewayClient` and updates state in the same request.
- **State transitions**: `suspended → active` on any successful Payment Attempt; `suspended → canceled` on the 3rd scheduled retry's failure *or* on a direct customer cancellation while `suspended`.
- **Bound**: the total attempt count per Invoice (initial + up to 3 scheduled retries) is enforced structurally — a self-service retry consumes one of the existing scheduled-attempt "slots" in narrative terms only; it does not add a 5th attempt. The exact bookkeeping (e.g., does a successful self-service retry between day 1 and day 3 cancel the still-pending day-3 attempt) must be resolved during implementation and is flagged as an open implementation detail, not a silently-decided one.
- **Module boundary**: `dunning`, per PRD §6, collaborating with `billing-job` (scheduled trigger), `payments` (charge execution), and `invoicing` (Payment Attempt attachment).

## Testing Decisions

- **Seam**: scheduled retries are tested through the billing-job runner seam (Testcontainers Postgres + WireMock gateway) — the same seam as Billing Execution Engine. Self-service retry is tested through the HTTP API seam (Spring Boot Test / MockMvc + Testcontainers Postgres), matching Subscription Lifecycle Management's seam.
- Required tests: full day 1/3/7 schedule executing correctly against a still-failing card, ending in cancellation; a self-service retry succeeding between scheduled attempts and restoring `active`; a self-service retry that itself fails, confirmed not to add an extra attempt beyond the bound; immediate cancellation from `suspended`.
- Contract-level assertions (decline/success payload shape) reuse the WireMock stub owned by Payment Gateway Integration rather than defining new fixtures.

## Out of Scope

- Dispute/chargeback-driven cancellation — a distinct trigger handled by Webhook Ingestion, not part of the Dunning schedule.
- Refunds.
- Grace periods before suspension (explicitly rejected — see `CONTEXT.md`'s "Avoid: past_due" guidance).
- Any admin-triggered retry or override.

## Further Notes

- `CONTEXT.md` explicitly warns against a "past_due"-style framing implying a grace period this system deliberately doesn't have — keep `suspended` semantics ("access cut off immediately") consistent in any code comments, error messages, or notification copy touched by this spec.
- The immediate-suspension-on-first-failure trade-off (harsher than a grace-period model, but unambiguous) is a deliberate product decision recorded in `docs/design/product-design.md` §3 — do not soften it during implementation without a product conversation.
