# Subscription Billing Engine — Product Requirements Document

Status: Draft, generated via `create-prd`
Source material: [`CONTEXT.md`](CONTEXT.md), [`docs/domain/domain-model.md`](docs/domain/domain-model.md), [`docs/design/product-design.md`](docs/design/product-design.md), [`docs/adr/`](docs/adr/), [`.claude/CLAUDE.md`](.claude/CLAUDE.md). No requirements were invented beyond what these documents establish; gaps are flagged explicitly in-line and summarized in §Assumptions.

---

## 1. Executive Summary

The Subscription Billing Engine is a backend system (with a thin self-service frontend) that manages the full lifecycle of B2C flat-rate monthly subscriptions: signup, trial conversion, recurring card-on-file charges, upgrades/downgrades, cancellation, payment-failure dunning, and chargeback handling — without any human (admin/support) in the loop for the common path.

The core value proposition is **correctness under retry and failure**: every financially significant operation (a charge, a webhook, a batch run) is idempotent, every dollar amount is exact (`BigDecimal`, never float), and every subscription state transition is durably audited. The system deliberately trades feature breadth for a small, well-defended v1 surface — one pricing model, one currency, one payment gateway, no admin overrides — so that the hard part (money handled correctly, exactly once) is solid before anything else is added.

The MVP goal is a working end-to-end loop: a customer can sign up (with or without a trial), get charged automatically every month on a stable anchor date, change or cancel their plan, recover from a failed card via self-service or automatic dunning, and receive a receipt — all provable via automated tests down to the "duplicate webhook / crashed batch job" edge cases, not just the happy path.

## 2. Mission

**Mission statement:** Make recurring billing boring — predictable, idempotent, and auditable — so that neither the business nor the customer is ever surprised by a charge.

**Core principles:**

1. **Exactly-once money movement.** Every externally triggered financial operation (charge, webhook) is idempotent by construction (unique constraints, dedupe keys), not by convention.
2. **Small, defensible surface area over speculative completeness.** Favor "explicitly out of scope, documented" over a half-built feature (see ADR-0002's rejection of a recoverable `disputed` state).
3. **State is provable, not just logged.** Every subscription transition produces an immutable audit record; application logs are for operators, the audit log is the system of record.
4. **No silent human override.** There is no admin/support role in v1 — every state change flows through a defined, testable code path, not a manual database edit outside of true incident response.
5. **Historical accuracy is non-negotiable.** A price change, plan retirement, or any later edit must never retroactively alter what a past invoice says was charged.

## 3. Target Users

### Primary persona: Self-service B2C subscriber ("Dana")

- Individual consumer in the US, signing themselves up for a personal or small-project subscription (no procurement process, no company card).
- **Technical comfort:** Non-technical to moderately technical; expects consumer-SaaS-grade UX (think Netflix/Spotify billing flows), not an enterprise billing portal.
- **Needs:** Know exactly what they'll be charged and when; be able to upgrade, downgrade, or cancel without contacting anyone; get a clear notification and recourse path when a card fails; get a receipt/PDF for their own records or expensing.
- **Pain points this system addresses:** Surprise charges, being silently locked out without warning, having to email support to cancel, losing money to a duplicate charge from a flaky retry.

### Secondary persona: Engineering/on-call operator

- Not a product user, but a direct consumer of this system's observability surface.
- **Needs:** Trust that the daily billing job either fully completes or is safely resumable; a Grafana dashboard that answers "did today's billing run correctly?" without reading logs; DLQ visibility when a Kafka consumer can't keep up.
- Explicitly **not** given an in-app admin/override UI in v1 — their only lever is metrics/logs/DB, by design (see `product-design.md` §3, and Risk R1 below).

### Explicitly out of scope for v1

- B2B buyers / procurement personas (no invoiced/Net-terms billing).
- Internal support/success agents needing a case-management or override console.
- Non-US customers (single-currency, no tax engine, no GDPR-erasure support).

## 4. MVP Scope

### In Scope

**Core Functionality**

- ✅ Free ($0) plan and flat-rate paid plans, monthly cadence only
- ✅ Optional per-signup Trial (card required upfront), auto-converts to paid unless canceled
- ✅ Subscription lifecycle: `trialing` → `active` → `pending_cancellation` / `suspended` → `canceled`
- ✅ Upgrade/downgrade (paid↔paid) scheduled for next Billing Cycle, at most one pending change
- ✅ Free→paid upgrade, applied immediately with an immediate charge
- ✅ Downgrade-to-free modeled as a plan change, not a cancellation
- ✅ Cancel-at-period-end from `active`, with undo before period end
- ✅ Immediate cancellation from `trialing` / `suspended` (nothing paid to protect)
- ✅ Daily idempotent batch billing job, fixed off-peak run time
- ✅ Card-on-file auto-charge via single gateway (sandbox/test mode)
- ✅ Dunning: 3 retries over ~7 days, immediate suspension on first failure
- ✅ Self-service payment retry while `suspended`
- ✅ Chargeback/dispute webhook → immediate, terminal cancellation
- ✅ One Invoice per Billing Cycle, up to 4 Payment Attempts underneath
- ✅ PDF receipt per successful Invoice
- ✅ Append-only audit log of every subscription state transition
- ✅ Versioned, effective-dated plan pricing; invoices snapshot the price charged
- ✅ Plan retirement (hidden from new signups, still billed for existing subscribers)
- ✅ Customer notifications: receipt, payment failure, trial-ending, cancellation confirmed

**Technical**

- ✅ Webhook idempotency by gateway event ID (unique-constraint dedupe)
- ✅ Billing-job idempotency via `(subscription_id, billing_period)` uniqueness
- ✅ `BigDecimal` for all monetary values
- ✅ Structured, correlated logs and Prometheus metrics for billing-critical operations
- ✅ Database migrations (Flyway) for every schema change

**Integration**

- ✅ One payment gateway behind an internal `PaymentGatewayClient` interface
- ✅ WireMock-stubbed gateway for offline/fast tests

**Deployment**

- ✅ Modular monolith, Docker Compose for local infra (Postgres, Kafka, Redis, Prometheus, Grafana)

### Out of Scope

- ❌ Per-seat, usage-based/metered, or tiered pricing models
- ❌ Multi-currency support / FX conversion
- ❌ Tax calculation or tax-provider integration
- ❌ B2B / invoiced / Net-terms billing
- ❌ Multiple concurrent subscriptions per customer
- ❌ Proration on plan changes
- ❌ Subscription pausing
- ❌ Refunds (self-service or admin-initiated)
- ❌ Internal admin/support role and financial-override tooling
- ❌ Recoverable dispute state / "dispute resolved" handling
- ❌ Sequential/gapless invoice numbering (EU VAT-style)
- ❌ GDPR erasure-on-request
- ❌ In-app reporting/analytics beyond Prometheus/Grafana metrics
- ❌ Signup/onboarding UI flow (frontend-owned; engine exposes the operations only)
- ❌ Multi-provider payment gateway abstraction

## 5. User Stories

1. **Free signup** — As a prospective customer, I want to start on a free plan with no payment method, so that I can try the product with zero commitment.
   _Example: Dana signs up with just an email; her subscription is `active` on the Free plan immediately, no card requested._

2. **Paid signup with trial** — As a customer, I want to start a trial on a paid plan by entering my card upfront, so that I get full access now but I'm not charged until the trial ends.
   _Example: Dana enters her card for the Pro plan; subscription enters `trialing`; 14 days later she's auto-charged and moves to `active`, or she cancels on day 10 and moves straight to `canceled`._

3. **Immediate paid signup** — As a customer, I want to subscribe to a paid plan without a trial, so that I get the plan and am billed starting today.
   _Example: Dana skips the trial option; she's charged immediately and her Anchor Date is set to today._

4. **Automatic renewal** — As a subscriber, I want my card charged automatically on my billing date every month, so that I never have to manually pay an invoice.
   _Example: The daily batch job finds Dana's subscription due, charges her card, generates an Invoice + PDF receipt, and emails her a receipt._

5. **Recover from a failed payment** — As a subscriber whose card was declined, I want automatic retries and the option to update my card and retry immediately, so that I don't lose access longer than necessary.
   _Example: Dana's renewal fails; she's suspended immediately; she updates her card two days later and triggers a retry herself rather than waiting for day-3 dunning._

6. **Change plans** — As a subscriber, I want to upgrade or downgrade my plan, so that my subscription matches my current needs.
   _Example: Dana schedules a downgrade from Pro to Basic; it takes effect at her next renewal, replacing any previously pending change._

7. **Cancel and change my mind** — As a subscriber, I want to cancel my subscription but keep access until the period I already paid for ends, with the option to undo, so that I don't lose access I've already paid for by mistake.
   _Example: Dana cancels on day 5 of a 30-day cycle; she keeps access through day 30; on day 20 she reactivates and stays `active`._

8. **Understand what happened to my money** — As a subscriber, I want a downloadable receipt for every successful charge, so that I have a record for my own accounting.
   _Example: Dana downloads the PDF receipt attached to last month's Invoice from her account._

### Technical user stories

- As an on-call engineer, I want the billing job and webhook handlers to be safely re-runnable, so that a crash mid-run or a gateway's at-least-once delivery never produces a double charge.
- As an on-call engineer, I want Prometheus metrics for payment success rate, billing-job latency, dunning counts, and Kafka consumer lag, so that I can tell whether today's billing run is healthy without reading raw logs.
- As a future maintainer, I want every price change to create a new `Price Version` rather than mutate the existing one, so that historical invoices are provably immune to later pricing changes.

## 6. Core Architecture & Patterns

**High-level approach:** Modular monolith (per `CLAUDE.md`), not microservices — no module in this domain currently has an independent scaling, deployment, or availability requirement that would justify splitting it out. Revisit only with a documented ADR if one emerges (e.g., a billing job that needs to scale independently of the API).

**Suggested module boundaries within the monolith:**

```
subscription-billing/
├── billing-core/          # Subscription, Plan, PriceVersion, lifecycle state machine
├── payments/              # PaymentGatewayClient interface + gateway adapter, PaymentAttempt
├── invoicing/             # Invoice generation, PDF receipt rendering
├── dunning/               # Retry scheduling, suspension/recovery logic
├── billing-job/           # Daily batch scan-and-charge job
├── webhooks/              # Inbound gateway webhook ingestion + dedupe
├── notifications/         # Outbound customer notification triggers (email)
├── audit/                 # Append-only AuditLogEntry writer
└── api/                   # REST controllers, OpenAPI-generated DTOs
```

**Key patterns:**

- **Transactional Outbox** for any event that must be reliably published to Kafka alongside a DB write (e.g., "subscription state changed" → notification trigger), so a DB commit and an event publish can't diverge.
- **Idempotency keys** at two layers: `(subscription_id, billing_period)` uniqueness for the billing job (ADR-0001), and `WebhookEventId` uniqueness for gateway webhooks.
- **State machine** for `Subscription.state`, enforced at the domain-model layer (not scattered `if` checks in controllers) — see the transition diagram in `docs/domain/domain-model.md`.
- **Versioned value objects** for pricing (`PriceVersion`) so an `Invoice` always references the exact price in effect at charge time, never "the plan's current price."
- **Resilience4j** circuit breaker + bounded retry around the gateway client, distinct from the *business-level* dunning retries — a gateway timeout is an infra concern, a declined card is a domain concern; don't conflate the two retry policies.
- **Repository pattern via Spring Data JPA**, no custom ORM abstraction beyond what Hibernate/Spring Data already provides (per "prefer existing abstractions").

## 7. Tools/Features

### 7.1 Subscription Lifecycle Management
- **Purpose:** Own the `Subscription` state machine and its invariants.
- **Operations:** create (free/trial/immediate-paid), schedule plan change, apply pending change at cycle boundary, cancel, undo-cancel.
- **Key features:** Enforces "one non-`canceled` Subscription per Customer," "at most one pending plan change," immediate vs. deferred cancellation rules by originating state.

### 7.2 Billing Execution Engine (daily batch job)
- **Purpose:** Find and charge every subscription due today or earlier.
- **Operations:** query `due_date <= today`, invoke Payment Attempt per due subscription, advance Anchor Date on success.
- **Key features:** Fixed off-peak run time (~03:00 UTC); `<=` query gives automatic missed-run catch-up with no separate backfill path (ADR-0001).

### 7.3 Payment Gateway Integration
- **Purpose:** Abstract the single sandbox payment gateway behind `PaymentGatewayClient`.
- **Operations:** charge card-on-file, receive webhook callbacks.
- **Key features:** WireMock-stubbed for tests; Resilience4j-wrapped for gateway-side transient failures (separate from dunning's business-level retries).

### 7.4 Dunning & Recovery
- **Purpose:** Recover revenue from a failed renewal without manual intervention.
- **Operations:** schedule retries at day 1/3/7, accept a customer-triggered retry at any point while `suspended`, cancel after the 3rd failure.
- **Key features:** Immediate suspension on first failure (no grace period); self-service retry doesn't reset or extend the schedule.

### 7.5 Invoicing & Receipts
- **Purpose:** Produce the durable financial record of each Billing Cycle.
- **Operations:** create Invoice on first Payment Attempt for a cycle, attach subsequent attempts, render PDF receipt on success.
- **Key features:** Exactly one Invoice per `(subscription, billing_period)`; snapshots the `PriceVersion` charged, immune to later price changes.

### 7.6 Webhook Ingestion
- **Purpose:** Consume gateway-pushed events (payment succeeded/failed, dispute opened) safely under at-least-once delivery.
- **Operations:** verify signature, dedupe by `WebhookEventId`, dispatch to the relevant handler (dunning, dispute).
- **Key features:** Unique-constraint-backed dedupe; a redelivered event is a no-op, not a re-application.

### 7.7 Notifications
- **Purpose:** Keep the customer informed at the four moments that matter.
- **Operations:** trigger on successful charge, failed charge, trial-ending-soon, cancellation-confirmed.
- **Key features:** Fires via the outbox pattern off the same transaction as the state change it reports; no notification on plan-change-scheduled, dispute, or self-service retry in v1.

### 7.8 Audit Logging
- **Purpose:** Provide an immutable, independent record of every subscription state transition.
- **Operations:** append-only write of who/what/when/old value/new value on every transition.
- **Key features:** Separate from application logs; never mutated or deleted (Invariant 12).

## 8. Technology Stack

| Layer | Technology |
|---|---|
| Backend language | Java 21+ (upgrade to latest LTS when appropriate) |
| Backend framework | Spring Boot 4 |
| Web/API | Spring Web |
| Security | Spring Security |
| Persistence | Spring Data JPA + Hibernate |
| Validation | Jakarta Bean Validation |
| Database | PostgreSQL |
| Migrations | Flyway |
| Messaging | Apache Kafka (Avro or Protobuf event schemas) |
| Reliable publish | Transactional Outbox pattern |
| Caching | Redis |
| Resilience | Resilience4j |
| Build | Maven |
| Frontend framework | React + TypeScript |
| Frontend build tool | Vite |
| UI library | MUI (Material UI) |
| Routing | React Router |
| Server state | TanStack Query |
| Forms / validation | React Hook Form + Zod |
| Charts | Recharts |
| API client | Generated from OpenAPI |
| Backend tests | JUnit 5, Mockito, AssertJ, Spring Boot Test, Testcontainers, WireMock |
| Frontend tests | Vitest, React Testing Library, Playwright |
| Local infra | Docker Compose (Postgres, Kafka, Redis, Prometheus, Grafana) |
| Deployment | Docker, Kubernetes, AWS (EKS, RDS Postgres, MSK, ElastiCache Redis, S3, CloudWatch, IAM, Secrets Manager) |
| IaC | Terraform |
| CI/CD | GitHub Actions, SonarQube/SonarCloud, Trivy, Dependabot |

**Note:** Specific dependency versions (Spring Boot 4.x patch, React 18/19, etc.) are not yet pinned anywhere in the repo — no `pom.xml` or `package.json` exists yet. Pin exact versions at project scaffolding time.

## 9. Security & Configuration

- **Authentication/authorization approach:** Not specified in any prior source document; this PRD's concrete decision, ratified by [`docs/adr/0003-jwt-bearer-auth-with-ownership-checks.md`](docs/adr/0003-jwt-bearer-auth-with-ownership-checks.md):
  - **Authentication:** Token-based, via Spring Security's OAuth2 Resource Server support — the customer-facing frontend obtains a bearer token (JWT) at login/signup and presents it on every subsequent request. No session cookies (keeps the API stateless and consistent with a SPA + generated OpenAPI client).
  - **Authorization:** Single role in v1 — `CUSTOMER`. There is no elevated/admin role, consistent with "no admin override" being a deliberate v1 decision (§4, Explicitly out of scope). Role membership alone is not sufficient: every request must also pass an **ownership check** — the authenticated customer's ID must match the `customerId` on the target Subscription/Invoice — enforced at the service layer (not just the controller), so a valid customer token can never read or mutate another customer's data (IDOR prevention).
  - **Webhook endpoint exception:** `POST /api/v1/webhooks/gateway` is not customer-authenticated at all — it authenticates the *gateway*, via signature verification (see the Webhook authenticity bullet below), before dedupe/processing.
  - **Signup exception:** `POST /api/v1/subscriptions` (free/trial/immediate-paid signup) is the one customer-facing endpoint that must be reachable pre-authentication, since it's how a new customer's identity and token are first established. The exact identity bootstrap (email+password, magic link, social login) is frontend/product-owned and out of this PRD's scope, but this engine must expose whatever "issue a token for this new customer" step that flow needs.
- **Configuration management:** Environment variables / Spring profiles for per-environment config; secrets (gateway API keys, DB credentials) via AWS Secrets Manager in production, `.env`/Docker Compose secrets locally — never hardcoded, never logged.
- **Webhook authenticity:** Gateway webhook signature verification is required before dedupe/processing (not explicitly stated in source docs but implied by "process gateway events idempotently and durably" — flagged as an assumption to confirm).
- **Security scope (in-scope):** Input validation (Jakarta Bean Validation) on all API inputs, authenticated/authorized access to subscription and invoice data, no sensitive data (full card numbers, secrets) in structured logs.
- **Security scope (out-of-scope for v1):** No PCI-scope card storage — card data lives with the gateway only (this system stores a gateway-provided token/reference, never raw PAN). No admin RBAC system, since no admin role exists.
- **Deployment considerations:** Standard Kubernetes deployment; billing job should run as a manner that guarantees single-execution-per-day semantics at the infra level (e.g., a Kubernetes CronJob) in addition to the DB-level idempotency constraint, as defense in depth.

## 10. API Specification

Per `CLAUDE.md`: REST, OpenAPI 3.x as source of truth, `/api/v1/...` versioning, structured error responses, idempotency required for non-idempotent (payment) operations. No OpenAPI file exists in the repo yet — the endpoints below are a **draft derived from the operations implied by the PRD/domain model**, to be formalized as `openapi.yaml` before frontend client generation.

| Method | Path | Purpose | Auth |
|---|---|---|---|
| `POST` | `/api/v1/subscriptions` | Create a Subscription (free signup, trial signup, or immediate-paid signup) | Public (identity-bootstrap exception, see §9) |
| `GET` | `/api/v1/subscriptions/{id}` | Fetch a Subscription's current state, plan, pending change, billing cycle | Bearer token; caller must own `{id}` |
| `POST` | `/api/v1/subscriptions/{id}/plan-change` | Schedule a plan change (upgrade/downgrade); replaces any pending change | Bearer token; caller must own `{id}` |
| `POST` | `/api/v1/subscriptions/{id}/cancel` | Cancel (deferred from `active`, immediate from `trialing`/`suspended`) | Bearer token; caller must own `{id}` |
| `POST` | `/api/v1/subscriptions/{id}/undo-cancel` | Reverse a `pending_cancellation` back to `active` | Bearer token; caller must own `{id}` |
| `POST` | `/api/v1/subscriptions/{id}/payment-methods` | Attach/replace the card on file | Bearer token; caller must own `{id}` |
| `POST` | `/api/v1/subscriptions/{id}/retry-payment` | Self-service retry while `suspended` | Bearer token; caller must own `{id}` |
| `GET` | `/api/v1/subscriptions/{id}/invoices` | List Invoices for a Subscription (cursor-paginated) | Bearer token; caller must own `{id}` |
| `GET` | `/api/v1/invoices/{id}` | Fetch a single Invoice + Payment Attempts | Bearer token; caller must own the parent Subscription |
| `GET` | `/api/v1/invoices/{id}/receipt` | Download the PDF receipt | Bearer token; caller must own the parent Subscription |
| `GET` | `/api/v1/plans` | List available (non-retired) Plans for signup | Public |
| `POST` | `/api/v1/webhooks/gateway` | Inbound gateway webhook receiver (payment success/failure, dispute) | Gateway signature verification (not a customer bearer token) |

**Authorization enforcement:** every "caller must own" row is checked at the service layer against the authenticated customer ID, not inferred from the path parameter alone — a structurally valid `{id}` belonging to another customer must fail with `403`, not leak existence via a `404` vs `403` distinction or a successful response. See §9.

**Idempotency:** State-changing customer endpoints (`plan-change`, `cancel`, `retry-payment`, `payment-methods`) should accept an `Idempotency-Key` header per `CLAUDE.md`'s "idempotency required for payment and other non-idempotent operations," so a client-side retry (e.g., a flaky mobile connection) can't double-apply an action.

**Example — create subscription with trial:**

```json
POST /api/v1/subscriptions
{
  "planId": "plan_pro_monthly",
  "useTrial": true,
  "paymentMethodToken": "gw_tok_abc123"
}
```
```json
201 Created
{
  "subscriptionId": "sub_9f2a...",
  "state": "trialing",
  "planId": "plan_pro_monthly",
  "trialEndsAt": "2026-09-03T00:00:00Z"
}
```

**Example — structured error response (per `CLAUDE.md`'s "consistent structured error responses"):**

```json
409 Conflict
{
  "error": {
    "code": "SUBSCRIPTION_ALREADY_CANCELED",
    "message": "This subscription is already canceled and cannot be modified.",
    "correlationId": "c7e1..."
  }
}
```

## 11. Success Criteria

**MVP success definition:** A customer can complete every lifecycle flow in §5 end-to-end against a sandboxed gateway with zero double-charges and zero silently-lost state transitions, verified by automated tests at every layer of the testing pyramid.

**Functional requirements**

- ✅ Free, trial, and immediate-paid signup all produce the correct initial state and (where applicable) Anchor Date
- ✅ The daily batch job charges every due subscription exactly once per `(subscription, billing_period)`, even under simulated crash/retry
- ✅ Dunning retries fire on schedule (day 1/3/7) and suspension/recovery/cancellation transitions match the state diagram exactly
- ✅ A redelivered webhook (same event ID) is provably a no-op
- ✅ Every state transition has exactly one corresponding audit log entry
- ✅ A price change never alters a previously issued Invoice's amount

**Quality indicators**

- Unit → integration (Testcontainers) → contract → E2E (Playwright) test coverage on all billing-critical paths, per the testing pyramid in `CLAUDE.md`
- Static analysis (SonarQube) and Trivy/Dependabot scans clean in CI before merge

**User experience goals**

- A customer always knows, without contacting support, what they'll be charged, when, and why access was suspended
- Cancel/undo-cancel and self-service retry are available with no admin involvement, at any hour

**Measurable success metrics**

- **Payment success rate**, first-attempt and post-dunning-recovery rate
- **Billing job completion:** 100% of due subscriptions processed within the daily window; zero double-charges (enforced by the idempotency constraint, monitored via a Prometheus counter that should stay at zero)
- **Invoice generation latency:** charge success → Invoice + receipt available
- **Involuntary churn** (dunning-exhausted + disputed) tracked separately from **voluntary cancellation**, since they imply different product responses

## 12. Implementation Phases

### Phase 1 — Foundation: Plans, Customers, and Subscription Lifecycle
**Goal:** The domain model and state machine exist and are fully testable, with no money movement yet.
- ✅ Flyway schema for Customer, Subscription, Plan, PriceVersion
- ✅ **Authentication & authorization:** Spring Security OAuth2 Resource Server configured to validate bearer JWTs; a `CUSTOMER` role plus a service-layer ownership check (authenticated customer ID == resource's `customerId`) enforced on every non-public endpoint per §10's Auth column; `401` on missing/invalid token, `403` on a valid token for the wrong customer
- ✅ Subscription state machine (all transitions from the domain model) with unit tests covering every edge in the diagram
- ✅ Seeded plan catalog (Free + at least one paid plan), plan retirement flag
- ✅ Free-plan signup flow, end-to-end
**Validation:** Every transition in `docs/domain/domain-model.md`'s state diagram has a passing unit test; invariants 1–5, 7, 8, 10 are enforced and tested; an integration test proves Customer A's token cannot read or mutate Customer B's Subscription/Invoice (`403`), and an unauthenticated request to any non-public endpoint is rejected (`401`).

### Phase 2 — Billing Execution: Charges, Invoices, Anchor Dates
**Goal:** Money moves correctly, exactly once, on schedule.
- ✅ `PaymentGatewayClient` interface + WireMock-stubbed adapter
- ✅ Daily batch job with `(subscription_id, billing_period)` idempotency constraint
- ✅ Anchor Date calculation + month-end clamping
- ✅ Invoice + Payment Attempt model, PDF receipt generation
- ✅ Trial signup and auto-conversion flow
**Validation:** Testcontainers-backed integration test proves a duplicate job run cannot double-charge; a full trial→active conversion test passes.

### Phase 3 — Failure Handling: Dunning, Webhooks, Disputes
**Goal:** The system recovers from payment failure automatically and safely.
- ✅ Webhook ingestion with `WebhookEventId` dedupe
- ✅ Dunning scheduler (day 1/3/7), immediate suspension, self-service retry endpoint
- ✅ Dispute webhook → immediate cancellation
- ✅ Plan change scheduling (upgrade/downgrade/free-toggle) and cancel/undo-cancel flows
**Validation:** Contract tests against the WireMock gateway stub cover success, decline, and dispute webhook payloads; a redelivered webhook integration test proves no-op behavior.

### Phase 4 — Records, Notifications, and Observability Hardening
**Goal:** The system is operable and auditable, ready for production traffic.
- ✅ Append-only audit log wired to every transition
- ✅ Outbox-backed notification triggers (receipt, failure, trial-ending, cancellation)
- ✅ Prometheus metrics + Grafana dashboards for all metrics listed in `CLAUDE.md`
- ✅ E2E (Playwright) coverage of the key user flows in §5
- ✅ CI pipeline: compile → unit → static analysis → integration → contract → Docker build → security scan → deploy → smoke test
**Validation:** All items in `CLAUDE.md`'s "Before Declaring a Feature Complete" checklist pass; Grafana dashboard answers "did today's billing run correctly?" without reading logs.

## 13. Future Considerations

- **Proration** on paid↔paid plan changes (currently deferred-to-next-cycle only) — likely the single most-requested post-MVP change once real users hit it.
- **Multiple pricing models**: per-seat, usage-based/metered, tiered — would require a significant billing-calculation redesign, not a bolt-on.
- **Multi-currency and tax** — needed before any non-US market entry; tax specifically implies a third-party tax provider integration.
- **Refunds** — self-service and/or admin-initiated; currently entirely absent, a known gap (Risk R1).
- **Admin/support console with scoped financial overrides** — the "no admin role" decision is a deliberate v1 simplification, not a permanent stance; revisit if manual DB fixes become frequent.
- **Subscription pause** — an alternative to cancel/re-subscribe for customers who want a temporary break without losing trial eligibility/history.
- **Dispute recovery** — a `disputed` state with a "dispute resolved" webhook handler, reverting to `active` (explicitly rejected for v1 in ADR-0002).
- **GDPR erasure-on-request** — required before any EU customer onboarding.
- **In-app reporting/analytics** beyond the existing Prometheus/Grafana operational metrics — e.g., a business-facing churn/MRR dashboard.
- **Sequential/gapless invoice numbering** — required if entering an EU-style VAT invoicing regime.
- **Multi-gateway abstraction** — currently a single gateway behind one interface; a second provider would validate whether that interface is actually gateway-agnostic.

## 14. Risks & Mitigations

| # | Risk | Mitigation |
|---|---|---|
| R1 | **No billing-error recourse.** "No refunds" + "no admin override" means a wrongly-charged customer has no in-app remedy; fixes require manual DB/gateway-dashboard intervention. | Accepted for v1 per `product-design.md` §3. Mitigate operationally with a documented, audited manual-fix runbook for on-call engineers; revisit if incident frequency justifies a scoped override tool. |
| R2 | **No dispute recovery.** A customer who wins a chargeback has no automatic path back to `active`. | Accepted per ADR-0002. Monitor dispute volume via metrics; add a recoverable `disputed` state if volume/complaints make the manual path untenable. |
| R3 | **No GDPR erasure support.** Only acceptable while US-only. | Gate any EU market-entry decision on erasure support being implemented first; treat as a hard blocker, not a fast-follow. |
| R4 | **Coarse billing-job timing precision.** A subscription's charge can land anywhere within the daily job's window, not at a precise moment (ADR-0001 trade-off). | Acceptable given monthly cycles; document the window explicitly in customer-facing copy ("charges process within 24 hours of your billing date") so it's never a surprise. |
| R5 | **Single-gateway dependency.** No multi-provider abstraction means a gateway outage stalls all billing, and switching providers later is a bigger lift than if abstracted from day one. | Mitigated structurally by the `PaymentGatewayClient` interface (Resilience4j-wrapped) even though only one implementation exists in v1 — keeps a future second adapter feasible without a rewrite. |
| R6 | **Auth/authorization approach.** §9 defines bearer-token auth + per-request ownership checks; now ratified by [`docs/adr/0003-jwt-bearer-auth-with-ownership-checks.md`](docs/adr/0003-jwt-bearer-auth-with-ownership-checks.md), which also settles the token-issuer question (self-issued JWTs, not a managed IdP, per the ADR's rejected-alternatives reasoning). | Resolved for v1. Revisit only if a future requirement (SSO, enterprise buyers, an admin console) makes a managed IdP worth the added dependency, per the ADR's own revisit condition. |

## 15. Appendix

**Related documents**

- [`docs/product/prd.md`](docs/product/prd.md) — the existing, narrower v1 PRD this document is being compared against
- [`CONTEXT.md`](CONTEXT.md) — domain glossary (ubiquitous language)
- [`docs/domain/domain-model.md`](docs/domain/domain-model.md) — entities, invariants, state transitions
- [`docs/design/product-design.md`](docs/design/product-design.md) — decision-by-decision requirements discovery log
- [`docs/adr/0001-daily-batch-billing-job.md`](docs/adr/0001-daily-batch-billing-job.md) — batch vs. per-subscription scheduling
- [`docs/adr/0002-dispute-cancels-immediately.md`](docs/adr/0002-dispute-cancels-immediately.md) — dispute handling trade-off
- [`docs/adr/0003-jwt-bearer-auth-with-ownership-checks.md`](docs/adr/0003-jwt-bearer-auth-with-ownership-checks.md) — customer auth mechanism and per-request ownership checks
- [`.claude/CLAUDE.md`](.claude/CLAUDE.md) — stack, engineering rules, definition-of-done checklist

**Key dependencies** (see §8 for the full stack table): Spring Boot 4, PostgreSQL, Apache Kafka, Redis, Resilience4j, Flyway, React + Vite + MUI, TanStack Query. No `pom.xml`/`package.json` exists yet to pin exact versions.

**Repository structure:** No application code exists yet (docs-only repo as of this PRD's generation). §6 proposes a starting module layout for the modular monolith.

---

## Assumptions Made

Because this repo currently contains requirements documentation but no code, most of this PRD's content is a faithful synthesis of `docs/product/prd.md`, `product-design.md`, `domain-model.md`, `CONTEXT.md`, and the two ADRs. Content genuinely **added** beyond those sources (and therefore worth flagging before treating this as authoritative):

- **§6 Core Architecture & Patterns** — the proposed module directory layout is new; the pattern names (Transactional Outbox, Resilience4j circuit breaker, repository pattern) are pulled from `CLAUDE.md`'s stack list but their specific application here is inferred, not sourced.
- **§9 Security & Configuration** — authentication/authorization mechanism is **undefined in all prior source documents**; the bearer-token + ownership-check approach in §9, the §10 Auth column, and the Phase 1 step in §12 are this PRD's own proposal, not sourced from `docs/product/prd.md` or `product-design.md`. Now ratified as [`docs/adr/0003-jwt-bearer-auth-with-ownership-checks.md`](docs/adr/0003-jwt-bearer-auth-with-ownership-checks.md) — see Risk R6.
- **§10 API Specification** — no OpenAPI file exists in the repo; every endpoint listed is inferred from the operations the PRD/domain model imply must exist ("create subscription," "attach payment method," etc.), not sourced from a spec.
- **§12 Implementation Phases** — phase sequencing/grouping is a reasonable but original breakdown; no source document specifies build order.

## Next Steps

1. ~~Ratify the authentication/authorization approach proposed in §9 (Risk R6) with an ADR.~~ Done — see [`docs/adr/0003-jwt-bearer-auth-with-ownership-checks.md`](docs/adr/0003-jwt-bearer-auth-with-ownership-checks.md).
2. Turn §10's draft endpoint list into a real `openapi.yaml`, since `CLAUDE.md` designates OpenAPI as the source of truth for HTTP APIs.
3. Once endpoints and auth are settled, this PRD is ready to hand to implementation planning (Phase 1 scope in §12).
