# Spec: Invoicing & Receipts

Source: `docs/product/product-prd.md` §7.5, §12 Phase 2. Module: `invoicing`.

## Problem Statement

Customers and the business both need a durable, accurate financial record of every Billing Cycle's charge — one that stays correct even after a later Plan price change, and that survives however many Payment Attempts (initial plus Dunning retries) it took to resolve.

## Solution

The `invoicing` module creates exactly one `Invoice` per `(subscription, billing_period)` on the first Payment Attempt for that cycle (Invariant 6), attaches every subsequent Payment Attempt — scheduled Dunning retry or self-service retry — to that same Invoice, and snapshots the `PriceVersion` actually charged so the amount is immune to later price changes (Invariant 9). On a successful Payment Attempt, it renders a downloadable PDF receipt. Customers can list, fetch, and download their own Invoices via the REST API.

## User Stories

1. As the system, I want an Invoice created on the first Payment Attempt of a Billing Cycle, so that Invariant 6 (one Invoice per cycle) holds from the very first attempt.
2. As the system, I want every subsequent Payment Attempt for that same cycle — scheduled retry or self-service retry — to attach to the existing Invoice, so that Dunning never produces duplicate Invoices for one cycle.
3. As the system, I want an Invoice to snapshot the exact `PriceVersion` charged, so that a later Plan price change can never retroactively alter what a past Invoice says was charged (Invariant 9).
4. As the system, I want an Invoice to reach a terminal status of `paid` or `failed` once a Payment Attempt succeeds or all attempts are exhausted, so that its status is unambiguous once Dunning concludes.
5. As a subscriber, I want a downloadable PDF receipt generated automatically the moment my charge succeeds, so that I have a record for my own accounting without asking anyone for it.
6. As a subscriber, I want to list my Invoices in reverse-chronological, cursor-paginated order, so that I can browse my billing history without loading everything at once.
7. As a subscriber, I want to fetch a single Invoice along with its underlying Payment Attempts, so that I can see exactly how many tries a charge took.
8. As a subscriber, I want to download the PDF receipt for a specific Invoice, so that I can attach it to an expense report.
9. As the system, I want ownership enforced on every Invoice read/download, so that Customer A can never list, fetch, or download Customer B's Invoices (`403`, per ADR-0003).
10. As the system, I want a normal database-generated identifier (UUID or auto-increment PK) for Invoices, so that no sequential/gapless numbering scheme is built for a US-only market that doesn't require one.
11. As a future maintainer, I want the PDF receipt to render the Plan name, the exact price charged (from the snapshot `PriceVersion`, not the Plan's current price), the charge date, and the Invoice identifier, so that the receipt is self-sufficient evidence of what happened.

## Implementation Decisions

- **Entities**: `Invoice` (JPA), owning the `(subscription_id, billing_period)` unique constraint jointly relied upon by the Billing Execution Engine's idempotency guarantee (ADR-0001); `PaymentAttempt` (JPA), 1..4 per Invoice.
- **Creation trigger**: an Invoice is created (or an existing one for that cycle is fetched) by whichever module drives the first charge attempt for a cycle — the billing job for scheduled renewals/retries, or the self-service retry endpoint for a customer-triggered attempt within an already-`suspended` cycle. This module owns the data model and the "one per cycle" guarantee; it does not itself decide *when* to charge.
- **Price snapshot**: the Invoice stores a reference to the specific `PriceVersion` charged at creation time, not a reference to the Plan (whose current price may change later).
- **PDF rendering**: a receipt-rendering component triggered on the transition to `paid` status. Choice of PDF library is an implementation detail to be decided at build time — not mandated by this spec.
- **Endpoints**: `GET /api/v1/subscriptions/{id}/invoices` (cursor-paginated, per CLAUDE.md's pagination convention), `GET /api/v1/invoices/{id}`, `GET /api/v1/invoices/{id}/receipt`, each with the standard JWT + ownership check.
- **Module boundary**: `invoicing`, per PRD §6.

## Testing Decisions

- **Seam**: read/list/download endpoints are tested via the HTTP API seam (Spring Boot Test / MockMvc + Testcontainers Postgres). Invoice-creation and one-per-cycle invariants are exercised indirectly by driving the Billing Execution Engine's job seam (Testcontainers + WireMock) and then asserting the resulting state through the HTTP API seam — not by unit-testing invoice creation in isolation from a real charge attempt.
- A narrow, additional component-level test validates the PDF receipt's structural content (contains Plan name, charged amount, date, Invoice ID) — this is a small addition layered onto the existing seams, not a new one.
- Required tests: one Invoice created per cycle even across multiple Dunning attempts; Invoice amount unchanged after a Plan price change that occurs after the Invoice was issued; ownership check on list/get/download; pagination behavior on the list endpoint.

## Out of Scope

- Refunds (no refund workflow touches an Invoice in v1).
- Tax line items (prices are treated as final/tax-exclusive).
- Sequential/gapless invoice numbering (EU VAT-style — explicitly out of scope for a US-only market).
- Multi-currency amounts.

## Further Notes

- Invariant 9 (price-change immunity) is the core correctness bar for this spec — any implementation that resolves an Invoice's amount by dereferencing the Plan's *current* price at read time, rather than the stored `PriceVersion` snapshot, is a bug, not an acceptable simplification.
- This module has no independent creation trigger of its own; it is deliberately driven by Billing Execution Engine and Dunning & Recovery rather than duplicating "should I charge now" logic.
