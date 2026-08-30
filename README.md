# Subscription Billing Engine

A B2C, single-currency, flat-rate recurring billing system built as a Spring Boot modular
monolith: subscribing customers to plans, running monthly charges, handling payment
failure (Dunning), gateway webhooks (disputes), invoicing/receipts, notifications, and an
append-only audit trail of every Subscription state transition.

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

Run the daily billing job manually (requires local infra already running):

```
make billing-job
```

In production this same entry point (`ApiApplication --job=billing-run`) is invoked by
the Kubernetes CronJob in [`k8s/billing-job-cronjob.yaml`](k8s/billing-job-cronjob.yaml).

### Configuration

`backend/api/src/main/resources/application.yml` documents every environment variable
(`DB_HOST`, `KAFKA_BOOTSTRAP_SERVERS`, `JWT_SECRET`, `STRIPE_API_KEY`, etc.); every value
has a dev-only default so the app runs against `docker-compose up` with no extra setup,
but every one of those defaults must be overridden in a real environment.

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

## Testing

```
cd backend
mvn -pl api -am test     # unit tests
mvn -pl api -am verify   # unit + Testcontainers-backed integration tests (needs Docker)
```
