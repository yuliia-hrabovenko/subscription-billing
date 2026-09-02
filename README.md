# Subscription Billing Engine

A B2C, single-currency, flat-rate recurring billing system built as a Spring Boot modular
monolith: subscribing customers to plans, running monthly charges, handling payment
failure (Dunning), gateway webhooks (disputes), invoicing/receipts, notifications, and an
append-only audit trail of every Subscription state transition.

## High-level design

Two clients — the customer-facing frontend and the Stripe webhook sender — talk to a
single Spring Boot API over HTTP. The API is a modular monolith: each module owns its
own domain rules, persistence, and data, and modules only ever talk to each other through
application interfaces, commands, domain events, or read models — never by reaching into
another module's tables directly. All modules share one PostgreSQL database, but table
ownership per module stays explicit (see [`docs/architecture/architecture.md`](docs/architecture/architecture.md)).

```
                 ┌────────────────────┐
   Customer ───▶ │                    │
                 │   Frontend (SPA)   │
   Admin ──────▶ │                    │
                 └─────────┬──────────┘
                           │ HTTPS, /api/v1, JWT bearer
                           ▼
   Stripe ─────▶ ┌────────────────────────────────────────────┐
   (webhook)     │                  api module                │
                 │   REST controllers · ApiApplication         │
                 └───┬─────────┬──────────┬──────────┬────────┘
                     ▼         ▼          ▼          ▼
              billing-core  billing-job  dunning   invoicing
                     │         │          │          │
                     ▼         ▼          ▼          ▼
                 payments   webhooks  notifications  audit
                     │                     │
                     ▼                     ▼
                  Stripe              Kafka outbox relay
                     │
                     ▼
                PostgreSQL (shared, one schema per module's ownership)
```

The daily billing job (`billing-job`) drives the recurring-charge flow: it finds due
Subscriptions, asks `billing-core` to advance them, and invokes `payments` to charge the
card. A failed charge hands off to `dunning`, which schedules retries; a Stripe dispute or
exhausted Dunning schedule cancels the Subscription. Every charge attempt produces an
`invoicing` record, every state transition is appended to `audit`, and customer-facing
events (receipts, dunning notices) are published to `notifications`' transactional outbox
for asynchronous delivery over Kafka. Inbound Stripe webhooks land in `webhooks`, which
verifies signatures, dedupes, and dispatches into the relevant module.

## Architecture

A modular monolith: modules are organized around business capabilities, each owning its
own domain rules, persistence model, and data — no cross-module JPA entities or database
joins. Modules communicate through application interfaces, commands, domain events, or
read models, never by reaching into another module's repositories directly.

| Module | Owns |
|---|---|
| [`billing-core`](backend/billing-core) | Subscription/Plan/PriceVersion domain model, persistence, and idempotency infrastructure |
| [`billing-job`](backend/billing-job) | Daily batch billing job: due-subscription scanning and Anchor Date advancement |
| [`dunning`](backend/dunning) | Failed-charge hand-off policy for the bounded Dunning retry schedule |
| [`invoicing`](backend/invoicing) | Invoice/PaymentAttempt schema, entities, and PDF receipts |
| [`payments`](backend/payments) | `PaymentGatewayClient` contract and the Stripe test-mode integration |
| [`webhooks`](backend/webhooks) | Inbound gateway webhook ingestion: signature verification, dedupe, dispatch |
| [`notifications`](backend/notifications) | Transactional-outbox-backed customer notifications |
| [`audit`](backend/audit) | Append-only `AuditLogEntry` record of every Subscription state transition |
| [`api`](backend/api) | REST controllers and the runnable Spring Boot application (`ApiApplication`) |

Each module is its own Maven artifact under [`backend/`](backend), aggregated by
[`backend/pom.xml`](backend/pom.xml). `api` is the only module that wires every other
module's port implementations together, so it's also where the runnable application and
the full-stack integration tests live.

## Tech stack

Java 21, Spring Boot 4, PostgreSQL (Flyway migrations), Kafka + Avro (Apicurio schema
registry) for the notifications outbox relay, Redis, Stripe (test mode) as the v1 payment
gateway, JWT bearer auth, Prometheus + Grafana for observability, Testcontainers for
integration tests.

## Running locally

Prerequisites: JDK 21+, Maven, Docker.

Start local infrastructure (Postgres, Kafka, schema registry, Redis, Prometheus, Grafana):

```
docker-compose up -d
```

Run the API:

```
cd backend
mvn -pl api -am install -DskipTests
mvn -pl api spring-boot:run
```

The API listens on `http://localhost:8080` (override with `SERVER_PORT`). Prometheus
scrapes `/actuator/prometheus`; Grafana is at `http://localhost:3001` (admin/admin).

Admin sign-in is via Auth0, which is SaaS-only — there's no bundled docker-compose
service for it. Full setup steps and troubleshooting live in
[`docs/operations/auth0-admin-sso-setup.md`](docs/operations/auth0-admin-sso-setup.md);
short version: create a free Auth0 Developer org, register a native/SPA OIDC application
(Authorization Code + PKCE, no client secret, redirect URI
`http://localhost:5173/admin/sso-callback`), register a custom API, and add a Post-Login
Action populating namespaced `groups`/`preferred_username` claims — then set
`ADMIN_SSO_ISSUER_URI`/`ADMIN_SSO_ROLE`/`ADMIN_SSO_CLAIM_NAMESPACE` (backend) and
`VITE_ADMIN_SSO_ISSUER_URI`/`VITE_ADMIN_SSO_CLIENT_ID`/`VITE_ADMIN_SSO_AUDIENCE`/
`VITE_ADMIN_SSO_CLAIM_NAMESPACE` (frontend, see
[`frontend/README.md`](frontend/README.md#admin-sso-dev)) to that org's values.

Run the daily billing job manually (requires local infra already running):

```
make billing-job
```

In production this same entry point (`ApiApplication --job=billing-run`) is invoked by
the Kubernetes CronJob in [`k8s/billing-job-cronjob.yaml`](k8s/billing-job-cronjob.yaml).

### Configuration

`backend/api/src/main/resources/application.yml` documents every environment variable
(`DB_HOST`, `KAFKA_BOOTSTRAP_SERVERS`, `JWT_SECRET`, `STRIPE_API_KEY`,
`ADMIN_SSO_ISSUER_URI`/`ADMIN_SSO_ROLE`, etc.). Every value has a dev-only default so the
app runs against `docker-compose up` with no extra setup, and every one of those defaults
must be overridden in a real environment — except `ADMIN_SSO_ISSUER_URI`/`ADMIN_SSO_ROLE`/
`ADMIN_SSO_CLAIM_NAMESPACE`, which have no working default at all (Auth0 is SaaS-only,
see above) and must be set even for local dev before the Admin portal will work.

## API

All endpoints are versioned under `/api/v1`. Bearer-token auth (JWT) is required except
for signup and the gateway webhook receiver.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/plans` | List the plan catalog |
| `POST` | `/api/v1/subscriptions` | Sign up (free, Trial, or immediate-paid) |
| `GET` | `/api/v1/subscriptions/{id}` | Fetch the caller's own Subscription |
| `POST` | `/api/v1/subscriptions/{id}/cancel` | Cancel (immediate or deferred, per originating state) |
| `POST` | `/api/v1/subscriptions/{id}/undo-cancel` | Reverse a pending cancellation |
| `POST` | `/api/v1/subscriptions/{id}/plan-change` | Schedule or apply a plan change |
| `POST` | `/api/v1/subscriptions/{id}/retry-payment` | Self-service Dunning retry while `suspended` |
| `GET` | `/api/v1/subscriptions/{id}/invoices` | List a Subscription's invoices |
| `GET` | `/api/v1/invoices/{id}` | Fetch an invoice |
| `GET` | `/api/v1/invoices/{id}/receipt` | Download an invoice's PDF receipt |
| `POST` | `/api/v1/webhooks/gateway` | Inbound Stripe webhook receiver |

## Frontend

A single Vite SPA under [`frontend/`](frontend) serving two separate portals off one
router (`AppRoutes`/`AdminRoutes`):

- **Customer portal** (`/`) — plan browsing, signup, the subscription dashboard, and
  invoice history/receipts.
- **Admin portal** (`/admin/*`) — read-only Customer/Subscription/Invoice lookup plus
  Plan catalog management (create, retire, add price versions).

The two portals are independent personas, not roles on one account: each has its own
login, its own JWT held in its own session store (`sessionStore` vs `adminSessionStore`),
and neither can act on the other's behalf. Customer login is email/password against this
service itself; Admin sign-in is SSO via Auth0 (docs/operations/auth0-admin-sso-setup.md)
— the SPA redirects to Auth0 directly (Authorization Code + PKCE) and this backend only
ever validates the
resulting token, never issues one itself. [`frontend/src/api/client.ts`](frontend/src/api/client.ts)
picks which store to attach a bearer token from per-request, based on whether the request
targets `/api/v1/admin/*`.

Stack: React 19, TypeScript, MUI, React Router, TanStack Query for server state, React
Hook Form + Zod for forms/validation, Recharts for the admin overview charts. See
[`docs/frontend/CLAUDE.md`](docs/frontend/CLAUDE.md) for conventions.

**API client**: OpenAPI is the source of truth. The backend's OpenAPI spec is synced
locally (`npm run sync:api-spec`) and turned into a typed schema (`npm run generate:api`)
consumed via `openapi-fetch`/`openapi-react-query` — no hand-written API types.

Structure:

```
frontend/src/
├── api/          generated OpenAPI schema + typed client, error mapping
├── auth/         per-persona session stores and auth contexts
├── components/   shared layout/loading/empty/error UI
├── features/     one folder per screen area (admin/*, invoices, plans, subscription)
├── routes/       AppRoutes (customer) + AdminRoutes, route guards
└── schemas/      Zod schemas for form validation
```

Running locally:

```
cd frontend
npm install
npm run dev          # http://localhost:5173, proxies to the API at VITE_API_BASE_URL
```

Testing:

```
npm run test          # Vitest + React Testing Library (unit/component)
npm run test:e2e       # Playwright, critical flows: signup, admin flow
npm run typecheck
npm run lint
```

## Testing

```
cd backend
mvn -pl api -am test     # unit tests
mvn -pl api -am verify   # unit + Testcontainers-backed integration tests (needs Docker)
```
