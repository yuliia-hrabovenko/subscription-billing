# Spec: Subscription Lifecycle Management

Source: `docs/product/product-prd.md` §7.1, §12 Phase 1. Module: `billing-core`.

## Problem Statement

A Customer needs to start, hold, change, and end a Subscription to a Plan such that, at every moment, their access level and future billing obligation are unambiguous — and provably correct, not just "probably correct because no one has hit the bug yet." Today no application code exists: the state machine, invariants, and customer-facing API described in `docs/domain/domain-model.md` and `docs/product/product-prd.md` must be built from scratch, with no admin/support role available to patch a Subscription into a sane state if the domain logic gets it wrong.

## Solution

Implement the `Subscription` entity and its lifecycle state machine (`trialing → active → pending_cancellation / suspended → canceled`) inside the `billing-core` module, enforcing Invariants 1, 2, 4, 5, 7, 8, 10, and 12 at the domain-model layer — never as scattered `if` checks in controllers. Expose the lifecycle through the customer-facing REST API defined in PRD §10 (create, get, plan-change, cancel, undo-cancel), protected by JWT bearer authentication and a mandatory service-layer ownership check per ADR-0003.

## User Stories

1. As a prospective customer, I want to sign up for the Free plan with no payment method, so that my Subscription is `active` immediately with zero commitment.
2. As a prospective customer, I want to sign up for a paid Plan with a Trial by entering my card upfront, so that I get access now and I'm not charged until the Trial ends.
3. As a prospective customer, I want to sign up for a paid Plan without a Trial, so that I'm charged immediately and my Anchor Date is set to today.
4. As a subscriber, I want to fetch my Subscription's current state, Plan, pending plan change, and Billing Cycle, so that I always know what I'm on and what's coming.
5. As a subscriber on a paid Plan, I want to schedule an upgrade or downgrade to another paid Plan, so that it takes effect at my next Billing Cycle without a mid-cycle proration calculation.
6. As a subscriber, I want to schedule a downgrade to the Free plan, so that my Subscription record continues (not canceled) and simply becomes free at the next Billing Cycle.
7. As a Free-plan subscriber, I want to upgrade to a paid Plan, so that I'm charged and granted access immediately rather than waiting for a "next cycle" that doesn't exist for a free Subscription.
8. As a subscriber with an already-pending plan change, I want scheduling a new plan change to replace the old one, so that at most one pending change ever exists (Invariant 4).
9. As a subscriber on an `active` paid Plan, I want to cancel at period end, so that I keep access through the cycle I've already paid for instead of losing it immediately.
10. As a subscriber who just canceled, I want to undo the cancellation before my period ends, so that I stay `active` and don't lose access I've already paid for by mistake.
11. As a subscriber in `trialing`, I want canceling to be immediate, so that I'm not kept in a pending state protecting access I was never charged for.
12. As a subscriber in `suspended`, I want canceling to be immediate, so that I'm not kept in a pending state protecting access that's already cut off.
13. As the system, I want to reject a second signup attempt for a Customer who already has a non-`canceled` Subscription, so that Invariant 1 ("at most one non-`canceled` Subscription per Customer") always holds.
14. As a subscriber who re-subscribes after a prior cancellation, I want a brand-new Subscription record with fresh Trial eligibility, so that `canceled` is provably terminal (Invariant 7) and no history carries over.
15. As a subscriber, I want plan-change and signup requests to reject a retired Plan, so that Invariant 10 (retired Plans stay valid for existing Subscribers but aren't selectable by new signups or plan changes) is enforced server-side, not just hidden in the UI.
16. As a subscriber, I want my own bearer token to grant access only to my own Subscription, so that a structurally valid Subscription ID belonging to another customer fails with `403`, not a `404` or a successful response (IDOR prevention, ADR-0003).
17. As an unauthenticated caller, I want every non-public endpoint to reject me with `401`, so that only `POST /api/v1/subscriptions` (signup) and `GET /api/v1/plans` are reachable without a token.
18. As a client with a flaky connection, I want to send an `Idempotency-Key` header on `plan-change`, `cancel`, and `undo-cancel` requests, so that a retried request can't double-apply the action.
19. As a subscriber, I want a clear, structured error (code, message, correlation ID) when I attempt an invalid transition (e.g., canceling an already-`canceled` Subscription), so that the client can present a sensible message.
20. As a future maintainer, I want every transition in the state diagram covered by a unit test at the domain layer, so that a regression in the state machine is caught before it reaches an integration test.

## Implementation Decisions

- **Entities**: `Customer`, `Subscription`, `Plan`, `PriceVersion` as JPA entities, Flyway-migrated. `Subscription.state` is the state-machine field; `pendingPlanChange` and `billingCycle` are nullable per Invariants 4 and 5.
- **State machine**: enforced at the domain-model layer (e.g., a `Subscription` aggregate method per transition — `cancel()`, `undoCancel()`, `schedulePlanChange()`), not in controllers or a generic status-setter. Every transition path must go through this layer.
- **Endpoints**: implement exactly the rows in PRD §10 owned by this module — `POST /subscriptions`, `GET /subscriptions/{id}`, `POST /subscriptions/{id}/plan-change`, `POST /subscriptions/{id}/cancel`, `POST /subscriptions/{id}/undo-cancel`, `GET /plans`.
- **Auth**: Spring Security OAuth2 Resource Server validates bearer JWTs (ADR-0003); a `CUSTOMER` role plus a service-layer ownership check (authenticated customer ID == resource's `customerId`) on every non-public endpoint.
- **Idempotency**: `Idempotency-Key` header handling on state-changing endpoints, deduped per customer within a bounded retention window — exact window length is an implementation detail, not a contract requirement.
- **Errors**: structured error response shape per PRD §10 example (`error.code`, `error.message`, `error.correlationId`) for every rejected transition.
- **Module boundary**: `billing-core`, per PRD §6's proposed module layout.

## Testing Decisions

- **Seam**: the HTTP API (Spring Boot Test / MockMvc against a running Spring context, backed by a real Testcontainers Postgres instance) — no mocked service layer, no mocked repository.
- Unit tests at the domain layer cover every edge in `docs/domain/domain-model.md`'s state diagram, independent of the HTTP layer.
- Integration tests via the API seam must prove: Customer A's token cannot read or mutate Customer B's Subscription (`403`); an unauthenticated request to a non-public endpoint is rejected (`401`); a duplicate signup for a Customer with an existing non-`canceled` Subscription is rejected; a retired Plan is rejected on both signup and plan-change.
- A good test here asserts observable API behavior (response status, response body, and post-condition state via a follow-up `GET`) — never internal method call counts or repository implementation details.
- No prior art exists yet in this codebase (docs-only repo); this module's tests establish the pattern subsequent specs' API-seam tests should follow.

## Out of Scope

- Actual money movement (charging a card) — owned by Billing Execution Engine and Payment Gateway Integration.
- Proration on plan changes (explicitly rejected for v1).
- Subscription pausing.
- Multiple concurrent Subscriptions per Customer.
- Any admin/support override of Subscription state.
- Refunds.

## Further Notes

- This spec corresponds to PRD §12 Phase 1's validation gate: "Every transition in the state diagram has a passing unit test; Invariants 1–5, 7, 8, 10 are enforced and tested; an integration test proves Customer A's token cannot read or mutate Customer B's data, and an unauthenticated request is rejected."
- ADR-0003 is authoritative for the auth/ownership design referenced above; do not re-derive it independently if requirements appear to conflict — flag the conflict instead.
- Audit Logging (see separate spec) rides on top of every transition implemented here; this spec's tests should assert on `AuditLogEntry` state as part of Phase 1, per the cross-cutting audit test suite described in that spec.
