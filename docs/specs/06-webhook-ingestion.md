# Spec: Webhook Ingestion

Source: `docs/product/product-prd.md` §7.6, §12 Phase 3. ADR: `docs/adr/0002-dispute-cancels-immediately.md`. Module: `webhooks`.

## Problem Statement

The payment gateway pushes events (payment succeeded, payment failed, dispute opened) to this system under at-least-once delivery, meaning the same event can arrive more than once. These events must be trusted only when genuinely from the gateway, and processed exactly once in effect — a redelivered event must be a provable no-op, never a re-application that could double-process an Invoice or re-trigger a state transition.

## Solution

The `webhooks` module exposes `POST /api/v1/webhooks/gateway`, authenticated by gateway signature verification rather than a customer JWT. Every verified event is deduped by `WebhookEventId` via a database uniqueness constraint (Invariant 13) before being dispatched to the relevant handler: payment-success/failure events feed into Dunning & Recovery's state logic, and a dispute-opened event transitions the Subscription straight to `canceled` — the same terminal state Dunning exhaustion reaches, per ADR-0002, with no recovery path.

## User Stories

1. As the gateway, I want to POST a payment-succeeded event, so that the corresponding Subscription/Invoice/Payment Attempt state reflects the successful charge.
2. As the gateway, I want to POST a payment-failed event, so that the corresponding Subscription enters (or remains in) the appropriate Dunning state.
3. As the gateway, I want to POST a dispute-opened (chargeback) event, so that the Subscription is immediately and terminally canceled, per ADR-0002.
4. As the system, I want every incoming webhook's signature verified before dedupe or processing, so that a forged request can never trigger a state transition.
5. As the system, I want a webhook whose `WebhookEventId` has already been processed to be a strict no-op, so that at-least-once gateway delivery can never double-process an Invoice or re-fire a state transition (Invariant 13).
6. As the system, I want the webhook endpoint reachable without a customer bearer token, so that the gateway (which has no customer identity) can call it at all — while still being fully authenticated via signature verification.
7. As the system, I want a malformed or unrecognized webhook payload rejected with a structured error, so that a gateway API change or misconfiguration fails loudly rather than silently corrupting state.
8. As the system, I want a dispute event on an already-`canceled` Subscription (e.g., canceled by Dunning exhaustion first) to be a safe no-op, so that overlapping cancellation triggers never conflict or double-fire.
9. As an on-call engineer, I want webhook processing outcomes observable (accepted, deduped-noop, rejected-signature, rejected-malformed) via metrics/logs, so that gateway integration health is visible without manually replaying events.
10. As a future maintainer, I want dispute handling to have no "resolved" or "recovery" path, so that ADR-0002's deliberate simplicity trade-off isn't silently reopened by a well-intentioned addition.

## Implementation Decisions

- **Endpoint**: `POST /api/v1/webhooks/gateway`, gateway-signature-authenticated (via `PaymentGatewayClient`'s signature-verification capability — see Payment Gateway Integration), not customer-JWT-authenticated.
- **Dedupe**: `WebhookEventId` unique constraint at the database level, checked *after* signature verification succeeds — signature verification must precede dedupe/processing (flagged as a confirmed requirement in PRD §9, not merely an assumption).
- **Dispatch**: verified, deduped events are routed by type — payment-success/failure to the Dunning module's state-transition logic; dispute-opened to a direct `Subscription → canceled` transition, bypassing Dunning entirely.
- **Dispute is a trigger, not a state**: per `CONTEXT.md`'s explicit glossary guidance ("Avoid: Chargeback as a distinct state — it is a trigger, not a lifecycle state"), no `disputed` state is introduced anywhere in this module.
- **No outbound Kafka publish for inbound processing**: webhook ingestion itself doesn't need the Transactional Outbox pattern (that pattern applies to *outbound* notification events, per Notifications spec) — it's a synchronous inbound HTTP call.
- **Module boundary**: `webhooks`, per PRD §6, collaborating with `payments` (signature verification), `dunning` (payment success/failure routing), and `billing-core` (dispute → cancel transition).

## Testing Decisions

- **Seam**: the HTTP endpoint (Spring Boot Test / MockMvc + Testcontainers Postgres), driven with WireMock-shaped payloads borrowed from the fixtures owned by Payment Gateway Integration — this module does not define its own separate gateway-payload fixtures.
- Required contract/integration tests: valid payment-success event processed correctly; valid payment-failure event routed to Dunning; valid dispute event cancels the Subscription immediately; invalid-signature event rejected before any state change; redelivered event (same `WebhookEventId`) proven to be a no-op — assert no second Invoice, Payment Attempt, state transition, or `AuditLogEntry` is created on redelivery.
- A good test here asserts on downstream state (Subscription state, Invoice/Payment Attempt count, audit entry count) after POSTing the same payload twice — not on whether an internal handler method was called a particular number of times.

## Out of Scope

- Dispute recovery / "dispute resolved" webhook handling (explicitly rejected — ADR-0002).
- Refunds triggered by any webhook event.
- Any webhook event type beyond payment success, payment failure, and dispute opened.
- Outbound notification delivery (a downstream effect of the state changes this module triggers, but owned by the Notifications spec).

## Further Notes

- ADR-0002 is authoritative for the dispute-cancels-immediately, no-recovery-path decision; do not add a recoverable state during implementation without a product conversation and a new ADR.
- Signature verification preceding dedupe/processing is treated here as a hard security requirement, not an optional hardening step — PRD §9 flags it as an assumption "to confirm," and this spec confirms it.
