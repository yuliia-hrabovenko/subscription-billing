# Spec: Payment Gateway Integration

Source: `docs/product/product-prd.md` §7.3. Module: `payments`.

## Problem Statement

The system needs to charge a Customer's card-on-file through a single external, sandboxed payment gateway, without coupling `billing-core`, `billing-job`, `dunning`, or `webhooks` to that gateway's specific API shape — and without a gateway-side transient failure (timeout, 5xx) being mistaken for, or triggering, a business-level Dunning cycle.

## Solution

Introduce a `PaymentGatewayClient` interface (the `payments` module) that abstracts "charge a card-on-file token" and "verify a webhook's authenticity" behind a stable, gateway-agnostic contract. One concrete adapter implements it against the chosen sandbox gateway. Resilience4j wraps the client with a circuit breaker and bounded retry for infra-level transient failures — a policy deliberately separate from Dunning's business-level retry schedule. A WireMock stub replicates the gateway's API shape so every other module's tests run offline and fast against this same contract.

## User Stories

1. As the billing job, I want to charge a card-on-file through `PaymentGatewayClient.charge(...)`, so that I never construct a gateway-specific HTTP request myself.
2. As the billing job or dunning module, I want a charge result that clearly distinguishes success, decline, and infra-error, so that I can route a decline to Dunning and an infra-error to the resilience layer instead.
3. As the system, I want a successful charge result to include a gateway-provided reference/token, so that the Invoice/Payment Attempt can record proof of the charge without storing raw card data.
4. As the system, I want the client to never receive, log, or persist a raw card number (PAN), so that the system stays out of PCI scope beyond storing a gateway-provided token.
5. As the system, I want repeated gateway failures to open a Resilience4j circuit breaker, so that a gateway outage doesn't queue up an unbounded pile of retrying billing-job charge attempts.
6. As the system, I want a single gateway timeout handled by a bounded retry at the infra layer, so that one slow response doesn't get misinterpreted as a declined card.
7. As the webhooks module, I want `PaymentGatewayClient` (or a closely related component) to verify a webhook's signature, so that only genuine gateway events are trusted before dedupe/processing.
8. As a test author in any other module, I want a WireMock stub that replicates the gateway's real API shape (success, decline, dispute, timeout), so that my tests don't need live gateway credentials or network access.
9. As a future maintainer, I want the gateway interaction hidden behind one interface, so that adding a second gateway provider later is an additive adapter, not a rewrite across every module that charges cards.

## Implementation Decisions

- **Interface**: `PaymentGatewayClient` with, at minimum, a charge operation (`charge(paymentMethodToken, amount) -> PaymentResult`) and a webhook-signature-verification operation. Exact method names/signatures are an implementation detail; the *contract* (success/decline/infra-error distinction, no raw PAN in or out) is the requirement.
- **Adapter**: exactly one concrete implementation for the chosen sandbox gateway in v1 — no multi-provider abstraction beyond what the interface itself provides "for free."
- **Resilience**: Resilience4j circuit breaker + bounded retry wraps the adapter, configured for infra-level transient failures only. This is explicitly a different retry policy from Dunning's business-level 3-retries-over-7-days schedule — the two must not be conflated in configuration or code.
- **Card data**: the adapter stores and forwards only the gateway-provided payment-method token/reference; raw PAN never enters this system's logs, database, or memory beyond the immediate call to the gateway SDK/API.
- **Test double**: WireMock stub shaped to match the real gateway's request/response contract (success, decline, dispute-webhook payload, and a slow/timeout scenario), owned by this module and reused by every other module's tests.
- **Module boundary**: `payments`, per PRD §6.

## Testing Decisions

- **Seam**: the `PaymentGatewayClient` interface, tested via contract tests against the WireMock stub — never via a hand-mocked implementation of the interface itself, in this module or any other.
- This is the one module where the "seam" and the "test double" are the same artifact: other specs (Billing Execution Engine, Dunning & Recovery, Webhook Ingestion) depend on this module's WireMock stub as shared infrastructure rather than each maintaining their own gateway mock.
- Contract tests must cover: successful charge, declined charge, gateway timeout (verifying the circuit breaker/retry engages), and webhook signature verification (valid and invalid signature).
- Prior art: none yet in this docs-only repo; this module's WireMock stub definitions are the reference other specs' tests should reuse rather than duplicate.

## Out of Scope

- Multi-gateway abstraction / a second real provider (future consideration — this interface exists to make that *feasible* later, not to build it now).
- Dunning's business-level retry scheduling (a separate, distinct retry policy — see Dunning & Recovery spec).
- Webhook event dedupe and business dispatch (signature verification lives here; dedupe and routing to dunning/lifecycle handlers live in Webhook Ingestion).
- Production gateway credentials/configuration (a deployment/Secrets Manager concern, not this module's).

## Further Notes

- Risk R5 (single-gateway dependency) is mitigated structurally by this interface, even though only one implementation exists in v1 — that structural mitigation *is* this spec's architectural justification.
- The circuit-breaker vs. dunning-retry distinction (infra concern vs. domain concern) is called out explicitly in PRD §6 and must not be collapsed into a single retry mechanism during implementation.
