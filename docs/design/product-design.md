# Subscription Billing Engine — Requirements Summary

Compiled from requirements-discovery discussion, 2026-08-20. No application code implements this yet — this is the product contract the implementation should follow.

## 1. Product shape

- **Pricing model**: Flat-rate recurring only for v1. Per-seat, usage-based/metered, and tiered pricing are explicitly out of scope.
- **Customer type & payment method**: B2C only. Card on file, auto-charged each cycle. No invoice-and-collect / Net-terms billing for businesses.
- **Currency**: Single currency only. No multi-currency support, no FX conversion.
- **Tax**: Out of scope for v1. Prices are treated as final/tax-exclusive; no tax provider integration, no in-engine tax rules.
- **Subscription cardinality**: One active subscription per customer. Switching plans replaces the current subscription rather than adding a second one.
- **Free plan**: A $0/forever plan exists as a real plan — not a trial. No payment method required. Still goes through the same subscription lifecycle as paid plans.
- **Target market**: US-only for v1. Consistent with the single-currency decision; also closes out the GDPR-erasure gap and the EU-style gapless-invoice-numbering question (see §5).

## 2. Subscription lifecycle

- **Trials**: Optional, decided per signup — a customer can sign up for a paid plan directly (immediate charge, anchor date = signup date) or opt into a trial. When used, payment method is required upfront and the subscription auto-converts to paid when the trial ends unless canceled first. A failed conversion charge follows the same dunning flow as any other renewal failure — no special-cased handling for a subscription's first charge.
- **Cancel during trial**: Immediate cancellation, not deferred — a trialing subscription hasn't been charged yet, so there's no paid access to protect by delaying it (unlike cancel-at-period-end for a paid subscription).
- **Cancel while suspended**: Also immediate — access is already cut off, so there's nothing left to protect by deferring.
- **Self-service payment retry**: A suspended customer can trigger an immediate retry after updating their card, rather than waiting for the next scheduled dunning attempt. Customer-initiated on their own subscription, so it doesn't reopen the "no admin role" decision.
- **Proration on plan changes**: No proration. Paid→paid plan changes take effect at the next billing cycle, not immediately.
  - *Why it matters*: Simplifies billing math significantly — no mid-cycle credit/charge calculations. Trade-off: a customer who upgrades today doesn't get the new plan until renewal.
- **Free → paid upgrade timing**: Exception to the rule above — upgrading from the free plan to a paid plan is immediate (charged and granted right away, not deferred to a next cycle).
  - *Why it matters*: The free plan has no real "current paid cycle" to wait out, so the general deferral rule doesn't apply cleanly. Treat as a distinct code path from paid→paid changes.
- **Downgrade to free**: Modeled as a plan change, not a cancellation. The same subscription record continues; its plan reference switches to the free plan at the next cycle, consistent with the general proration rule.
- **Pending plan changes**: A subscription holds at most one scheduled plan change at a time. Selecting a new one replaces whatever was already pending rather than queuing behind it.
- **Cancellation & access**: Cancel-at-period-end — access continues in full through the remainder of the paid cycle, then the subscription simply doesn't renew.
- **Undo cancellation**: A customer can reactivate any time before period end, canceling the pending cancellation.
- **Pause**: Out of scope for v1. Only active/canceled states exist — a customer who wants to stop temporarily cancels and re-subscribes later, losing trial eligibility and starting a fresh cycle.

## 3. Payments & failure handling

- **Payment gateway**: One real gateway (sandbox/test mode) behind an internal `PaymentGatewayClient` interface — no multi-provider abstraction. WireMock stubs the same API shape for fast/offline tests.
- **Payment failure handling**: Automatic dunning — retry the charge 3 times over roughly 7 days (e.g. day 1, day 3, day 7), then cancel the subscription if every retry fails.
- **Invoice vs. payment attempt**: One Invoice per Billing Cycle regardless of how many charge attempts it takes — the initial attempt plus up to 3 dunning retries are separate Payment Attempt records underneath the same Invoice, not separate invoices.
- **Access during dunning**: Suspended immediately on the *first* failed attempt — not retained until retries are exhausted. Access is restored automatically if a retry succeeds.
  - *Why it matters*: Harsher than a grace-period model, but removes ambiguity about what a "suspended but still trying" customer can do.
- **Webhook idempotency**: Dedupe by gateway event ID — a unique constraint on the processed event ID means a redelivered webhook is recognized and skipped.
  - *Why it matters*: Gateways deliver at-least-once, so the same "payment succeeded" event can arrive twice. Without dedupe, that's a double-processed invoice.
- **Refunds**: Out of scope for v1. No self-service or admin-initiated refund path — all sales final within the engine.
- **Billing-error recourse**: Accepted as a v1 risk. "No refunds + no admin override" means there is no in-app way to correct a billing mistake — fixes happen by hand against the DB/gateway dashboard. Deliberately not solved by adding an override endpoint, since that would reopen the "no admin role" decision.
- **Chargebacks & disputes**: A dispute-opened webhook from the gateway transitions the subscription straight to `canceled` — the same terminal state as exhausted dunning, not a new recoverable state. No "dispute resolved/won" handling in v1; a customer who wins a dispute re-subscribes.

## 4. Billing execution

- **Billing cadence**: Monthly only.
- **Anchor date**: Clamped to the last valid day of the month (Stripe-style) — a Jan 31 signup bills Feb 28/29, then Mar 31, Apr 30, etc. Only meaningful once a subscription is on a paid plan — a free subscription has no billing cycle or anchor date; one starts fresh, anchored to that day, the moment it first becomes paid.
- **Billing job model**: A daily scheduled batch job, fixed at an off-peak time (e.g. 03:00 UTC), scans for subscriptions due to renew and charges them.
  - *Why it matters*: Must be idempotent per subscription + billing period (e.g. a unique constraint on `subscription_id + billing_period`) so a crash-and-restart or duplicate run can never double-charge.
- **Missed billing runs**: No separate backfill mechanism. The due-subscriptions query is `due_date <= today`, not `== today`, so a skipped day (deploy, outage) is caught up automatically by the next run.
- **Billing job alerting**: Metrics-only for v1 (job duration, subscriptions processed, failures) via the existing Prometheus/Grafana stack — no separate paging system.
- **Plan price changes**: Existing subscribers are migrated to a new price at their next renewal — not grandfathered, not applied immediately.
  - *Why it matters*: Plans need versioned, effective-dated pricing, and each invoice needs to snapshot the price actually charged — not just reference "the plan's current price" — so historical invoices stay accurate after a price change.
- **Plan catalog management**: Static seed configuration (e.g. shipped via Flyway migration), not a runtime/admin-managed feature — consistent with "no admin role."
- **Plan retirement**: A plan can be closed to new signups (hidden from selection) while still billing existing subscribers on it at its current/versioned price. A visibility change, not a deletion — no runtime tooling needed, just another seeded attribute.

## 5. Records & compliance

- **Invoice scope**: An internal DB record (line items, amount, status, timestamps) plus a downloadable PDF receipt made available to the customer. No sequential/gapless invoice numbering requirement — US-only market means no EU VAT-style invoicing regime applies. A normal DB-generated identifier (UUID or auto-increment PK) is sufficient.
- **Notifications**: Four events trigger customer email — payment receipt on success, payment failure alerts, trial-ending reminder, and cancellation confirmation.
- **Roles & authorization**: Customer self-service only. No internal admin/support role and no financial-override tooling in v1.
- **Audit trail**: A dedicated, append-only audit log records every subscription state transition (who/what/when/old value/new value), separate from regular structured application logs.
- **Data retention**: Retain billing/invoice history indefinitely. No erasure-on-request mechanism in v1.
  - *Why it matters*: Would not be GDPR-compliant, but the target-market decision (US-only, §1) closes this gap for v1 — revisit if EU customers are ever in scope.
- **Onboarding/signup flow**: Out of scope for the engine's requirements. The engine exposes the underlying operations (create subscription, attach payment method); the flow that sequences them is a frontend/product concern.
- **Reporting & analytics**: Nothing beyond the Prometheus/Grafana metrics already listed in CLAUDE.md (churn, payment success rate, etc.) — no in-app reporting API or dashboard in v1.

## 6. Open questions

None currently open. All items raised during discovery (target market, plan catalog management, billing-error recourse, chargebacks/disputes, billing job scheduling/alerting, onboarding-flow scope, reporting scope, invoice numbering, GDPR exposure) have been resolved and folded into §1–§5 above.
