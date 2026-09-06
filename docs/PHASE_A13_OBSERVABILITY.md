# Phase A13 — Audit, Reporting & Observability Hardening

> **Status:** ✅ Complete (2026-09-06 — full suite green: 285 tests, 0 failures)
> **Date:** 2026-09-05 → 2026-09-06
> **Scope:** cross-cutting production hardening before client integration — audit
> coverage sweep, read-only reporting dashboards, Actuator/metrics exposure control,
> JSON structured logging with trace correlation, rate limiting on expensive
> endpoints, and the final OpenAPI contract + static `openapi.json` artifact.
>
> **Readiness note:** this documents *technical* readiness only. Operational validation
> (RBAC in production, multi-instance rate-limit semantics, secrets injection, SMTP/ClamAV
> endpoints) remains out of scope and is covered by the deployment phases.

---

## 1. Overview

Phase A13 closes the cross-cutting backend concerns required before the Front Office,
Back Office, and Mobile teams integrate. It preserves every A0–A12 business rule,
security boundary, and Clean Architecture gate. All six workstreams from
`agent/implementation_plan_phaseA13.md` are implemented, tested, and green.

| # | Workstream | Deliverable | New tests (33 total) |
|---|---|---|---|
| 1 | Audit coverage sweep | Audit calls on every security/business-sensitive mutation | `AuditCoverageChecklistTest` (1), `AuditWriteIntegrationTest` (4) |
| 2 | Audit viewer | `GET /api/audit` + `GET /api/audit/{id}`, ADMIN-only | `AuditControllerSecurityTest` (3) |
| 3 | Reporting / read endpoints | `/api/reports/*` dashboards (read-only, DB-side aggregates) | `ReportControllerTest` (9), `ReportingQueryPlanTest` (2) |
| 4 | Actuator & metrics | Minimal exposure; health/info public, metrics/prometheus ADMIN-only | `ActuatorSecurityTest` (4) |
| 5 | Structured logging & correlation | JSON logstash lines, MDC `traceId`, redacted rejection values | `StructuredLoggingTest` (4) |
| 6 | Rate limiting / abuse protection | `@RateLimited` sliding window, HTTP 429 + `Retry-After` on uploads & AI | `RateLimitingTest` (3) |
| 7 | Final OpenAPI contract | Annotations everywhere, bearer scheme, pagination schema, `openapi.json` | `OpenApiContractTest` (3) |

Preserved pre-existing gates (not new, not counted in the 33): `CleanArchitectureFitnessTest`
(3) and `MethodSecurityFitnessTest` (1). 252 (A12 baseline, given) + 33 (new A13 test
methods, all in new files) = 285 final Maven total; the only modified test file,
`StegBackendApplicationTests`, still contributes its original 2 tests.

---

## 2. Audit coverage sweep (workstream 1)

**Goal:** every security/business-sensitive mutation is audited through the existing A2
mechanism, verified by a deterministic checklist rather than by hand.

**Decision:** all auditing flows through the single centralized writer
(`AuditService.log(...)`), called directly by every service — this is the mechanism the
checklist enforces (each listed service must contain an `auditService` wiring). The
annotation-driven AOP path (`@Audited` / `AuditAspect`) exists in the codebase but is
currently applied to zero business methods, so it enforces nothing today; no new AOP
plumbing was introduced.

Covered mutating operations (each produces an `AuditLog` entry with actor, action code,
target, and traceId). The checklist (`AuditCoverageChecklistTest`, 31 entries) asserts
each action literal is still emitted by its responsible service **and** that the service
wires the audit writer; renaming or dropping a listed action fails the build with the
exact missing action (demonstrated: temporarily renaming `CONVERSATION_MEMBER_LEFT` in
`MessagingService` fails the test with
`CONVERSATION_MEMBER_LEFT not found in messaging/application/MessagingService.java`).

- **Authentication / security:** login, refresh, logout, logout-all.
- **Role / permission changes:** there is deliberately no dedicated role-mutation endpoint
  in the codebase (verified: no role-grant/revoke/setter exists outside the `User` entity).
  The only runtime role assignment is registration, which assigns the fixed seeded
  `CANDIDATE` role (`AuthService`); that surface is covered by `USER_REGISTERED`.
- **Workflow transitions:** application + internship state machines
  (`WorkflowService`, including AI-assisted logbook generation).
- **Restricted / sensitive document operations:** upload, restricted download
  (IP-validated), application/internship attach + verify.
- **Conversation membership changes:** `CONVERSATION_MEMBER_ADDED / REMOVED / LEFT /
  REJOINED` (all four in the checklist).
- **FinanceCase / PaymentApproval / Certificate operations:** finance analyst
  `ai-finance-analyze` outputs, payment approvals, certificate issuance.
- **AiRecommendation review actions:** every `ACCEPTED_BY_HUMAN` / `DISMISSED` decision
  is traceable.

`AuditCoverageChecklistTest` asserts the required action codes are auditable, and
`AuditWriteIntegrationTest` verifies a real mutation writes the expected entry.
A new read-side was added: `GET /api/audit` (paginated, filterable by action/entity/actor)
and `GET /api/audit/{id}`, both ADMIN-only (`AuditControllerSecurityTest` 3/3).

## 3. Reporting (workstream 2)

Read-only `/api/reports/*` Back Office dashboards — no mutation of authoritative state.

| Endpoint | Aggregation | Role |
|---|---|---|
| `/api/reports/applications-by-status` | application count by status | HR / DIRECTOR / ADMIN |
| `/api/reports/internships-by-type` | internship count by type | HR / DIRECTOR / ADMIN |
| `/api/reports/internships-by-status` | internship count by lifecycle status | HR / DIRECTOR / ADMIN |
| `/api/reports/internships-by-department` | distinct internships by destination department | HR / DIRECTOR / ADMIN |
| `/api/reports/finance-cases-by-status` | finance case count by status | FINANCE / DIRECTOR / ADMIN |
| `/api/reports/payment-totals` | paid amounts by calendar year/month × department | FINANCE / DIRECTOR / ADMIN |

- All aggregation runs database-side (single JPQL `GROUP BY` projection per report, no
  entity loading) in the reporting read-model; `V24__reporting_read_model_indexes.sql`
  adds supporting indexes.
- `ReportingQueryPlanTest` (2 tests) asserts precisely two things: (a) the seven V24
  index names exist in `pg_indexes`, and (b) handwritten SQL statements mirroring the six
  aggregates each yield a valid `EXPLAIN (FORMAT JSON)` plan. It does **not** execute the
  repository queries, does **not** count SQL statements, and does **not** execute a
  read-only assertion — so N+1-freedom is by construction (one projection query per
  aggregate, verified by code inspection of `JpaReportQueryRepository`), not measured.
  `ReportControllerTest` (9/9) covers every role matrix, including forbidden roles.

### Payment-totals semantics (`GET /api/reports/payment-totals`)

Exactly what the two JPQL queries in `JpaReportQueryRepository` compute:

- **Payment date:** `PaymentReceipt.paymentDate` (`LocalDate`), set to the issuance day
  when the receipt is created; rows with `NULL` paymentDate are excluded.
- **Included statuses:** no status predicate exists — every receipt with a non-null date
  is summed. In practice this is exactly the issued set: receipts are created directly as
  `ISSUED`, `GENERATED` never persists with a date, and `VOIDED` is a defined-but-never-
  assigned status (no void/cancel/refund endpoint or service path exists anywhere).
- **Department source:** the internship's *current* `InternshipAssignment.destination`
  (`code`), reached via receipt → financeCase → internship → assignment. The unfiltered
  query uses `LEFT JOIN`, so receipts whose internship has no current assignment roll up
  under `departmentCode: null`; the `departmentId`-filtered variant uses an inner join on
  `destination.id`, and an unknown id returns 404.
- **Currency:** `currencyCode` is stored per receipt (default `TND`) but is **not**
  grouped or converted — the `SUM(amount)` is only meaningful under single-currency
  operation, which holds today.
- **Date filtering:** none — no `from`/`to` parameters; the report covers full history
  grouped by calendar `(year, month)` in descending order.

## 4. Actuator & metrics (workstream 3)

Final exposure contract (`ActuatorSecurityTest` 4/4, `method-security` gate 1/1):

- Exposed endpoints: `health`, `info`, `metrics`, `prometheus`. All probes enabled.
- **Public:** `health` (aggregate UP/DOWN only), `health/liveness`, `health/readiness`,
  `info` (build stamp only). `show-details` / `show-components` = `when_authorized` so
  anonymous probes never see component detail.
- **ADMIN-only:** `metrics/**`, `prometheus/**`. Everything else (`env`, `configprops`,
  `beans`, `threaddump`, `heapdump`, `loggers`, mappings, `shutdown`, unknown ids) is
  **not exposed on the web port at all** — the test asserts anonymous gets 401 and ADMIN
  never sees a 2xx for any of them.
- `mail` health contributor explicitly disabled (`management.health.mail.enabled: false`) —
  SMTP is not a serving dependency and an unreachable relay must not drag the aggregate
  health DOWN. This fixed a pre-existing aggregate-DOWN condition.
- Discovery quirk (documented, reverted): setting `management.endpoints.web.discovery
  .enabled=false` breaks the `/actuator` root (500); discovery stays default.
  `/actuator` root is ADMIN-gated 2xx and leaks no data.

## 5. Structured logging & correlation (workstream 4)

- `TraceIdFilter` (matches the A0 `X-Trace-Id` contract): reads/creates `traceId`,
  stores it in MDC, re-emits it as the response `X-Trace-Id` header, and runs before the
  security chain. `JwtAuthenticationFilter` resolves its envelope traceId from the same MDC
  key, preserving the existing error-envelope behavior exactly.
- `logback-spring.xml`: **always-JSON** output via `LogstashEncoder` with
  `@timestamp`, `service: steg-backend`, flattened MDC, and the logger/level fields —
  one machine-parseable JSON line per event, no profile mode toggling.
- `pom.xml`: `logstash-logback-encoder:8.1` (Logback 1.5.38).
- **Secrets policy (verified by `StructuredLoggingTest`):** validation rejects are logged
  with `rejectedValue` replaced by `[REDACTED]` (the client-facing envelope still echoes the
  original value). A full audit of every `log.*` call in `src/main` confirms: request
  bodies are never logged; JWTs/access/refresh tokens are never logged (refresh tokens are
  handled by hash only; auth exception messages are static); the Gemini API key and SMTP
  credentials are never logged (provider/model/byte-length diagnostics only);
  CIN/national IDs are never logged; document/file contents are never logged (metadata
  only: reference, filename, MIME, size); AI prompts/responses are never logged (IDs and
  response byte-length only); the constraint-violation handler does not log at all.
- **Precisely scoped residuals (not secret leakage):** user emails appear in a handful of
  operational lines (`AuthService` register/login/refresh/lock, `StompRealtimeNotifier`
  delivery-failure warning) — PII, unchanged by A13. JWT/STOMP debug lines (jjwt messages,
  never token content) are silent under the INFO root in production. Downstream
  `e.getMessage()` lines (storage, ClamAV, notification delivery, retry sweep) log library
  messages containing no credentials by construction. `DataIntegrityViolationException`
  messages are logged at WARN and can echo DB unique-key *values* (e.g. an email from a
  duplicate-key error) — pre-existing behaviour, unchanged by A13. The generic-exception
  handler logs the full stack trace, as required for debugging.
- **MDC lifecycle:** `TraceIdFilter` clears the MDC in a `finally` block, so pooled
  servlet threads cannot leak one request's traceId into the next. There is no
  `@Async`/`CompletableFuture`/executor anywhere: all domain events are synchronous
  `@TransactionalEventListener(BEFORE_COMMIT)` on the request thread, and the Gemini
  outbound call is a synchronous `RestClient` on the request thread — the traceId is
  therefore present in every downstream log line of the request. The `@Scheduled` retry
  sweep runs on scheduler threads with an empty MDC (uncorrelated lines, no leakage).
- Test caveats captured in the test: MockMvc must `.addFilters(new TraceIdFilter())`
  (servlet-context filters are not applied by `springSecurity()`), and Logback snapshots MDC
  at deferred-processing time, so the snapshotting appender must capture during the request.

## 6. Rate limiting (workstream 5)

- `@RateLimited(name, limit, windowSeconds)` marker on controller methods; the enforcing
  `RateLimitingAspect` (sliding window, in-memory) in `common.infrastructure.ratelimit`.
- Buckets are keyed **per endpoint × per authenticated principal** (anonymous falls back to
  IP), so an abusive caller cannot starve other users or other endpoints.
- Rejection throws `RateLimitExceededException` **before** the target method runs (the LLM
  call / disk write never happens), mapped by `GlobalExceptionHandler` to **429** with a
  `Retry-After` header on a standard `ErrorEnvelope`.
- Wired endpoints: document upload (10/min) and all five AI endpoints
  (analyze 20/min, finance analyze 20/min, logbook generate 10/min, assistant query
  20/min, recommendation review 20/min).
- **Multi-instance limitation (documented in the aspect javadoc):** counters are
  process-local; a multi-replica deployment sees `limit × replicas` per bucket and should
  back this with a shared store (Redis). Single-instance operation keeps the guarantee exact.
- Memory bounded (4 096 bucket cap, timestamp pruning; degrades to allow at cap).
- `RateLimitingTest` (3/3): burst → exactly-limit 200s then 429 + Retry-After; a different
  user is unaffected; a different endpoint of the same user is unaffected.
- ArchUnit note: the marker annotation lives in `common.domain.annotation` (not
  infrastructure) so interface controllers can reference it without violating layering.

## 7. OpenAPI final contract (workstream 6)

- `OpenApiConfig` already declared the global `BearerAuth` JWT scheme — kept. Public
  endpoints (`register`, `login`, `refresh`, `universities`) opt out with
  `@SecurityRequirements` so the contract matches real access control; the contract test
  asserts public ops expose no security and protected ops require Bearer (locally or via
  the top-level global security item).
- Full annotation pass on the two REST controllers that had none: `DocumentController`
  (tag + 9 endpoints: summaries, restricted-download semantics, 429 on upload, 4xx matrix)
  and `WorkflowController` (tag + 5 endpoints: state/transition/audit-trail with
  validation, 403, 404, 422 responses).
- Pagination: springdoc resolves Spring Data `Pageable` to the `pageable` query param →
  `Pageable` schema `{page,size,sort}` and produces real `Page<T>` response schemas
  (e.g. `PageAuditLogResponse`); the property
  `springdoc.model-converters.pageable-converter.enabled` is enabled explicitly.
- WebSocket STOMP endpoints (`MessagingStompController`) are not part of the REST OpenAPI
  contract — WS contracts are documented separately in `PHASE_A9_WS_CONTRACTS.md`.
- **Artifact (precise mechanism):** `OpenApiContractTest` fetches the live `/v3/api-docs`
  from the running application context, validates it (JSON validity, explicit 14-path
  endpoint set, Bearer scheme, per-operation summaries, public/protected security parity,
  pagination schema, 429 on upload), and writes the spec to
  `target/openapi/openapi.json` — this happens on every test run, including full
  `clean test`. No Maven plugin generates the spec, and nothing copies it automatically:
  `docs/openapi.json` is a manually-committed snapshot of that generated file for the
  client teams (OpenAPI 3.1.0, 103 paths, 88 schemas; refresh it by re-running the
  contract test and copying `target/openapi/openapi.json` over it).
  `package -DskipTests` produces no spec. Caveat, verified: consecutive generations are
  *semantically* identical but not always *byte*-identical — schema property *order*
  (e.g. inside `PageableObject`) is nondeterministic; the contract test therefore guards
  semantics (paths, schemas, security), never bytes.

## 8. Test run & A13 inventory

```
./mvnw clean test --no-transfer-progress
Tests run: 285, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| Area | Test class (surefire `Tests run`) | Count |
|---|---|---|
| Audit sweep | `AuditCoverageChecklistTest` | 1 |
| Audit sweep | `AuditWriteIntegrationTest` | 4 |
| Audit viewer | `AuditControllerSecurityTest` | 3 |
| Reporting | `ReportControllerTest` | 9 |
| Reporting | `ReportingQueryPlanTest` | 2 |
| Actuator | `ActuatorSecurityTest` | 4 |
| Logging | `StructuredLoggingTest` | 4 |
| Rate limit | `RateLimitingTest` | 3 |
| OpenAPI | `OpenApiContractTest` | 3 |
| Gates (pre-existing) | `CleanArchitectureFitnessTest` | 3 |
| Gates (pre-existing) | `MethodSecurityFitnessTest` | 1 |

Reconciliation: 252 (A12 baseline, given) + 33 (new A13 test methods, each verified
executing in its surefire report above) = 285 final Maven total. The total is exact:
285 `<testcase>` elements across the 48 surefire XML reports, 0 failures/errors. The
`.txt` summaries sum to only 211 because `@Nested` parent classes report `Tests run: 0`
in `.txt` under surefire 3.5.6 + JUnit Platform 6.0.3 while their nested children (present
in the parent XML, e.g. `AuthIntegrationTest.xml` with 13 cases) still execute and count
toward the Maven total. The only modified test file, `StegBackendApplicationTests`, still
contributes its original 2 tests — no pre-existing test was added, removed, or altered.

PASS items: all six workstreams + final full-suite run, OpenAPI artifact produced and valid,
git diff/status is A13-scoped only.

PENDING (out of scope, pre-existing or deployment-time only):
- JUnit `@Nested` containers (e.g. `AuthIntegrationTest`, `JwtServiceTest`,
  `AiServiceGracefulDegradationTest`) report `Tests run: 0` in their surefire `.txt`
  under surefire 3.5.6 + JUnit Platform 6.0.3, but their children execute and are fully
  recorded in the parent XML (e.g. `AuthIntegrationTest.xml` holds 13 cases) and count
  toward the exact Maven total of 285. Pre-existing project-wide tooling quirk; no test
  is silently skipped.
- Unknown/nonexistent paths produce a 5xx envelope because no `NoResourceFoundException`
  handler exists and the generic `Exception` handler catches it. This is pre-existing
  technical debt, unchanged by A13 — and it is **not** considered ideal REST behaviour
  (a 404 is expected); it is left untouched only to avoid changing unrelated behaviour
  in this phase.
- `/api/public/**` remains a reserved permitAll prefix with no production handlers.
- Production RBAC, secrets injection, and multi-instance rate-limit scaling are deployment
  concerns, not code.

---

## 9. Key files

```
src/main/java/tn/steg/backend/common/
├── infrastructure/logging/TraceIdFilter.java          # A13: trace correlation filter
├── infrastructure/ratelimit/RateLimitingAspect.java   # A13: sliding-window limiter
├── domain/annotation/RateLimited.java                  # A13: endpoint marker
├── domain/exception/RateLimitExceededException.java    # A13: 429 mapping target
├── interfaces/rest/GlobalExceptionHandler.java         # +429 handler w/ Retry-After
└── infrastructure/config/{SecurityConfig,OpenApiConfig}.java
src/main/resources/logback-spring.xml                   # always-JSON LogstashEncoder
src/main/resources/application.yml                      # actuator + springdoc config
src/main/java/tn/steg/backend/reporting/**              # A13 reporting read-model
src/main/java/tn/steg/backend/audit/interfaces+application/**  # audit viewer (A13)
src/main/resources/db/migration/V24__reporting_read_model_indexes.sql
docs/openapi.json                                       # A13 static OpenAPI artifact
src/test/java/tn/steg/backend/observability/
├── ActuatorSecurityTest.java  ├── StructuredLoggingTest.java
├── RateLimitingTest.java      └── OpenApiContractTest.java
```