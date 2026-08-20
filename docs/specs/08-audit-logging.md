# Spec: Audit Logging

Source: `docs/product/product-prd.md` §7.8, §12 Phase 4. Module: `audit`.

## Problem Statement

Every Subscription state transition must be provably recorded — who, what, when, old value, new value — as an independent, immutable record the business can trust even if application logs are rotated, lost, or otherwise untrusted. There is no admin/support role to manually reconcile a disputed history, so the audit trail must be the actual system of record, not a best-effort log line.

## Solution

The `audit` module provides an append-only `AuditLogEntry` writer, invoked by every module that performs a Subscription state transition (`billing-core`, `billing-job`, `dunning`, `webhooks`). Each write happens in the same database transaction as the transition it records — strict atomicity, not eventual delivery. No update or delete path is exposed for `AuditLogEntry` at all, at the code level, not merely by convention (Invariant 12).

## User Stories

1. As the system, I want every Subscription state transition to write exactly one `AuditLogEntry`, so that Invariant 12 holds regardless of which module or code path triggered the transition.
2. As a future maintainer, I want each `AuditLogEntry` to capture the actor (system, customer, or gateway), the old state, the new state, and a timestamp, so that I can reconstruct exactly what happened and why.
3. As the system, I want a transition triggered by the billing job (e.g., suspension on a failed renewal) attributed as system-initiated, so that automated vs. human-triggered changes are distinguishable in the record.
4. As the system, I want a transition triggered directly by a customer action (cancel, undo-cancel, plan-change taking effect) attributed to that customer, so that customer-initiated changes are distinguishable from automated ones.
5. As the system, I want a transition triggered by a gateway webhook (dispute → canceled) attributed as gateway-initiated, so that externally-triggered changes are distinguishable from both of the above.
6. As the system, I want the audit write to happen in the same transaction as the state change it records, so that a transition can never commit without its corresponding audit entry, and vice versa.
7. As the system, I want `AuditLogEntry` rows to have no update code path, so that a record, once written, cannot be silently altered by a future bug or a well-intentioned "fix."
8. As the system, I want `AuditLogEntry` rows to have no delete code path, so that the audit trail can never be shortened, even accidentally.
9. As an operator or future maintainer, I want to reconstruct a Subscription's full state history purely from its `AuditLogEntry` rows, so that the audit log is genuinely usable as the system of record, not just a compliance checkbox.
10. As a future maintainer, I want a single cross-cutting test suite that walks every transition in the domain model's state diagram and asserts exactly one audit entry per transition, so that audit coverage doesn't silently regress as new transition-triggering code is added elsewhere.

## Implementation Decisions

- **Entity**: `AuditLogEntry` (JPA) with, at minimum: `subscription_id`, `actor_type` (`system` / `customer` / `gateway`), `old_state`, `new_state`, `occurred_at`, and a `correlation_id` to tie the entry back to the originating request/job-run/webhook for cross-referencing with structured logs and traces.
- **Write pattern**: co-located, strictly atomic write in the same transaction as the state change — deliberately *not* the Transactional Outbox pattern used by Notifications, since audit correctness requires immediate, guaranteed atomicity rather than eventual delivery to an external system.
- **No mutation surface**: the repository/DAO for `AuditLogEntry` exposes only an insert/append operation — no update or delete method exists in the code, enforced structurally rather than by policy or convention.
- **Callers**: every module that changes `Subscription.state` (billing-core, billing-job, dunning, webhooks) calls into this module's writer as part of its own transition transaction; this module has no independent trigger of its own.
- **Module boundary**: `audit`, per PRD §6 — but note it is a dependency of nearly every other module rather than a peer with an independent API surface of its own.

## Testing Decisions

- **Seam**: none standalone. Audit correctness is asserted as an additional assertion layered onto the seams already used by the modules that trigger transitions — the HTTP API seam (Subscription Lifecycle Management) and the billing-job runner seam (Billing Execution Engine, Dunning & Recovery). After driving each transition, assert exactly one new `AuditLogEntry` exists with the correct `old_state`/`new_state` pair.
- A single, dedicated cross-cutting test suite should enumerate every edge in `docs/domain/domain-model.md`'s state diagram once and assert the audit-entry postcondition for each — rather than duplicating that assertion inside every other spec's test file. Other specs' tests may still spot-check audit entries where convenient, but this suite is the authoritative coverage.
- A good test here asserts on the persisted `AuditLogEntry` row set (count, `old_state`/`new_state`/`actor_type` values) — never on whether a writer method was called, since that would test implementation rather than the actual durable record.

## Out of Scope

- A customer-facing or admin-facing audit-log read API (not present in PRD §10's endpoint list).
- Audit log export or reporting tooling.
- Non-subscription audit trails (e.g., logging payment-gateway calls or hypothetical admin actions — no admin role exists in v1).

## Further Notes

- Invariant 12 makes this the one module explicitly required to have *zero* mutation paths in its repository layer — worth flagging explicitly to reviewers as an intentional design constraint, not an oversight or missing feature.
- Because this module has no independent seam, its implementation should land alongside (or slightly ahead of) Subscription Lifecycle Management in Phase 1, so every subsequent phase's transitions have an audit writer to call into from day one.
