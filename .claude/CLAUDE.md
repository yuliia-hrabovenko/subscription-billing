# Project

Subscription Billing Engine.

## Stack

## Frontend

- **Framework:** React
- **Language:** TypeScript
- **Build Tool:** Vite
- **UI Library:** MUI (Material UI)
- **Routing:** React Router
- **Server State:** TanStack Query
- **Forms:** React Hook Form
- **Validation:** Zod
- **Charts:** Recharts
- **API Client:** TypeScript client generated from OpenAPI
- **Unit/Component Testing:** Vitest + React Testing Library
- **E2E Testing:** Playwright

## Backend

- **Language:** Java 21+ (upgrade to the latest LTS when appropriate)
- **Framework:** Spring Boot 4
- **Web/API:** Spring Web
- **Security:** Spring Security
- **Persistence:** Spring Data JPA + Hibernate
- **Validation:** Jakarta Bean Validation
- **Database:** PostgreSQL
- **Database Migrations:** Flyway

## Distributed Systems

- **Messaging:** Apache Kafka
- **Event Serialization:** Avro or Protobuf
- **Reliable Event Publishing:** Transactional Outbox Pattern
- **Caching:** Redis
- **Resilience:** Resilience4j
- **Idempotency:** Application-level idempotency keys
- **Retries:** Exponential backoff with bounded retries
- **Dead Letter Handling:** Kafka DLQ topics

## API & Contracts

- **API Style:** REST
- **API Specification:** OpenAPI 3.x
- **Contract:** OpenAPI is the source of truth for HTTP APIs
- **Frontend API Client:** Generated from OpenAPI
- **Versioning:** `/api/v1/...`
- **Error Format:** Consistent structured error responses
- **Pagination:** Cursor-based pagination where appropriate
- **Idempotency:** Required for payment and other non-idempotent operations

## Testing

### Backend

- JUnit 5
- Mockito
- AssertJ
- Spring Boot Test
- Testcontainers
- WireMock

### Frontend

- Vitest
- React Testing Library
- Playwright

### Testing Strategy

Prefer the following testing pyramid:

1. Unit tests
2. Integration tests
3. Contract/API tests
4. End-to-end tests
5. Load/performance tests

Use Testcontainers for infrastructure-dependent integration tests instead of mocking PostgreSQL/Kafka behavior.

## Observability

- **Telemetry:** OpenTelemetry
- **Metrics:** Prometheus
- **Dashboards:** Grafana
- **Logging:** Structured JSON logs
- **Tracing:** Distributed tracing with OpenTelemetry
- **Correlation:** Propagate correlation/trace IDs across HTTP and Kafka

Important business metrics include:

- Payment success/failure rate
- Payment processing latency
- Billing job latency
- Invoice generation latency
- Kafka consumer lag
- Retry count
- DLQ message count
- Subscription churn
- API latency
- Database connection pool utilization

## Infrastructure

### Local Development

Use Docker Compose for local infrastructure:

- PostgreSQL
- Kafka
- Redis
- Prometheus
- Grafana

### Production

- **Containers:** Docker
- **Orchestration:** Kubernetes
- **Cloud:** AWS
- **Infrastructure as Code:** Terraform

Potential AWS services:

- EKS
- RDS PostgreSQL
- MSK
- ElastiCache Redis
- S3
- CloudWatch
- IAM
- Secrets Manager

Do not introduce a managed AWS service unless there is a documented reason for using it.

## CI/CD

- **CI/CD:** GitHub Actions
- **Build:** Maven
- **Static Analysis:** SonarQube/SonarCloud
- **Security Scanning:** Trivy
- **Dependency Scanning:** Dependabot or equivalent
- **Container Images:** Docker
- **Deployment:** Kubernetes

Recommended pipeline:

1. Compile
2. Unit tests
3. Static analysis
4. Integration tests
5. Contract tests
6. Build Docker images
7. Security scans
8. Publish artifacts
9. Deploy
10. Smoke tests

## Architecture

Start with a **modular monolith** rather than multiple microservices.

Extract a module into a separate service only when there is a concrete architectural reason, such as:

- Independent scaling requirements
- Independent deployment lifecycle
- Clear bounded context
- Different availability requirements
- Significant workload isolation
- Infrastructure/resource isolation

Do not introduce microservices solely for demonstration purposes.

## Engineering rules

- Do not introduce dependencies without justification.
- Do not modify architecture without an ADR.
- Financial calculations must use BigDecimal.
- Never use floating point for monetary values.
- All externally triggered financial operations must be idempotent.
- Every database schema change requires a migration.
- New behavior requires tests.
- Never disable tests to make the build pass.
- Do not silently change public APIs.
- Prefer existing abstractions over creating new ones.

## Before Declaring a Feature Complete

Before declaring any feature complete, verify all of the following:

### Requirements

- [ ] All functional requirements for the feature are implemented.
- [ ] Acceptance criteria are satisfied.
- [ ] Edge cases and failure scenarios have been considered.
- [ ] No requirements were silently omitted or changed.
- [ ] Any ambiguity or assumption has been documented.

### Design & Architecture

- [ ] The implementation follows the existing architecture and module boundaries.
- [ ] Domain invariants are explicitly enforced.
- [ ] Transactions are used where atomicity is required.
- [ ] Idempotency is implemented for operations that may be retried.
- [ ] Asynchronous operations use appropriate retry/DLQ behavior.
- [ ] No unnecessary abstractions, frameworks, or dependencies were introduced.
- [ ] Significant architectural decisions are documented in an ADR.

### Backend

- [ ] API endpoints follow the OpenAPI specification.
- [ ] Input validation is implemented.
- [ ] Authorization rules are enforced.
- [ ] Error responses are consistent.
- [ ] Database migrations are included when the schema changes.
- [ ] Queries and indexes are appropriate for expected data volume.
- [ ] External integrations have timeout, retry, and failure handling.
- [ ] Payment/billing operations cannot accidentally execute twice.

### Frontend

- [ ] UI implements the required user flows.
- [ ] Loading, empty, success, and error states are handled.
- [ ] Form validation is implemented.
- [ ] API errors are presented appropriately.
- [ ] Authorization/permissions are respected in the UI.
- [ ] The UI works on supported screen sizes.
- [ ] Accessibility basics are satisfied.

### Testing

- [ ] Unit tests cover important business logic.
- [ ] Integration tests cover database and infrastructure interactions.
- [ ] Contract/API tests are updated where necessary.
- [ ] E2E tests cover critical user flows.
- [ ] Failure scenarios are tested.
- [ ] Tests are deterministic and do not depend on execution order.
- [ ] All tests pass locally.

### Observability

- [ ] Important operations produce useful structured logs.
- [ ] Errors are observable and actionable.
- [ ] Relevant metrics are exposed.
- [ ] Distributed tracing is preserved across service boundaries where applicable.
- [ ] Important billing/payment events can be correlated using trace/correlation IDs.

### Security

- [ ] Authentication and authorization are enforced.
- [ ] Sensitive data is not logged.
- [ ] Secrets are not hardcoded.
- [ ] User input is validated and safely handled.
- [ ] Dependencies do not introduce known critical vulnerabilities.

### Performance & Reliability

- [ ] No obvious N+1 queries or unnecessary database calls exist.
- [ ] External calls have appropriate timeouts.
- [ ] Retry behavior is bounded and does not amplify failures.
- [ ] Kafka consumers are idempotent.
- [ ] Resource usage is reasonable.
- [ ] Performance-sensitive code has been evaluated against expected workload.

### Code Quality

- [ ] Code follows project conventions.
- [ ] No dead code or debugging statements remain.
- [ ] No TODOs were added unless intentionally tracked.
- [ ] Public APIs and complex business logic are documented where necessary.
- [ ] No duplicated logic was introduced unnecessarily.
- [ ] Static analysis passes.
- [ ] Formatting/linting passes.

### Final Verification

Before reporting the feature as complete:

1. Review the implementation against the requirements.
2. Run the relevant test suite.
3. Run static analysis and formatting checks.
4. Review the database/API changes.
5. Review error and failure paths.
6. Check the Git diff for unintended changes.
7. Confirm documentation is updated.
8. Confirm no known issues remain unreported.

If any required check fails, **do not declare the feature complete**. Report what remains and why.
