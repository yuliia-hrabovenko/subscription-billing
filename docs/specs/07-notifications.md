# Spec: Notifications

Source: `docs/product/product-prd.md` §7.7, §12 Phase 4. Module: `notifications`.

## Problem Statement

Customers need to be proactively informed at the moments that affect their money or access — without having to check the app — and a notification failure must never be allowed to diverge from, or block, the underlying state change it reports.

## Solution

The `notifications` module triggers on exactly four events (successful charge, failed charge, trial-ending-soon, cancellation-confirmed), publishing via the Transactional Outbox pattern: an outbox row is written in the same database transaction as the state change it reports, guaranteeing the notification-to-send and the state change can never diverge, even if the downstream publish/send fails or the process crashes immediately after commit.

## User Stories

1. As a subscriber, I want a receipt email when a charge succeeds, so that I have immediate confirmation of what I was billed.
2. As a subscriber, I want a payment-failure alert email when a charge fails, so that I know immediately why my access was suspended and what to do about it.
3. As a subscriber on a Trial, I want a reminder email before my Trial ends, so that I'm not surprised by the auto-conversion charge.
4. As a subscriber, I want a cancellation-confirmed email whether I canceled from `active` (deferred) or from `trialing`/`suspended` (immediate), so that I have written confirmation my cancellation was recorded.
5. As the system, I want no notification fired for a scheduled-but-not-yet-applied plan change, so that only the four defined trigger events generate customer email, per current scope.
6. As the system, I want no notification fired for a dispute event, so that dispute handling stays a silent, terminal state transition rather than an emailed one (consistent with no recovery path existing to explain).
7. As the system, I want no notification fired for a self-service payment retry, so that only the outcome (success → receipt, or the original failure alert already sent) generates email, not the retry attempt itself.
8. As the system, I want the outbox row for a notification written in the exact same database transaction as the state change it reports, so that a notification is never enqueued for a state change that gets rolled back, and never lost for one that commits.
9. As the system, I want a crash or restart after the triggering transaction commits to still eventually deliver the notification, so that the outbox row (not yet published) survives and is picked up by the relay on recovery.
10. As the system, I want a downstream email-send failure to never roll back the underlying state change, so that a broken email provider can't block a customer's cancellation or charge from taking effect.
11. As an on-call engineer, I want visibility into unpublished/stuck outbox rows, so that a relay outage is detectable via metrics rather than discovered from a customer complaint.

## Implementation Decisions

- **Outbox**: an `OutboxEvent` entity (event type, payload, `created_at`, `published_at`) written in the same transaction as the triggering write (e.g., the same transaction that flips a Subscription to `canceled`, or that marks an Invoice `paid`).
- **Relay**: a separate poller/publisher reads unpublished outbox rows and publishes them to Kafka using the Avro/Protobuf event schemas designated in PRD §8; a downstream Kafka consumer performs the actual email send. The specific email provider/transport is a deployment concern, out of this spec's scope.
- **Trial-ending-soon detection**: requires a scheduled scan (either riding the daily billing-job run or its own lightweight daily scan) to find Trials approaching `trialEndsAt`. The exact lead time (e.g., N days before conversion) is an implementation detail to decide at build time, not specified by prior source documents — flag as an assumption if a specific value is chosen.
- **Scope discipline**: exactly four trigger events, matching the Business Rule in `domain-model.md` — no plan-change-scheduled, dispute, or self-service-retry notification is added, even if it seems like an easy addition.
- **Module boundary**: `notifications`, per PRD §6, consuming triggers from `billing-core`, `billing-job`, `dunning`, and `webhooks` without those modules depending back on `notifications` for anything synchronous.

## Testing Decisions

- **Seam**: assert that an outbox row is enqueued with the correct event type and payload, in the same transaction as the triggering state change, via Testcontainers Postgres. This is the seam of record for this spec — do **not** assert that an email was actually sent or received; that would require mocking a real email provider, which is a lower-confidence, higher-maintenance seam than asserting the outbox contract this module actually owns.
- If the relay/Kafka-consumer/email-send chain is implemented within this phase, validate it as a separate, narrower test against the outbox-to-Kafka boundary only — not by re-testing the four trigger conditions again at that layer.
- Required tests: each of the four trigger events produces exactly one outbox row of the correct type; a rolled-back transaction (e.g., a failed state change) produces zero outbox rows; the five explicitly-excluded events (plan-change-scheduled, dispute, self-service-retry, and any other transition) produce zero outbox rows.

## Out of Scope

- In-app notification center or notification history UI.
- SMS or push notifications.
- Marketing/promotional email.
- Admin-configurable notification preferences (no admin role exists).
- The email provider integration itself and its delivery-failure handling beyond "doesn't roll back the state change."

## Further Notes

- This is the one module where the Transactional Outbox pattern is prescribed by CLAUDE.md/PRD §6 as the mechanism, not a design choice left open to the implementer — do not substitute a direct synchronous email call from within the triggering transaction.
- No prior notification code exists in this docs-only repo; this spec's outbox schema should be treated as the pattern any future outbound-event module in this system follows.
