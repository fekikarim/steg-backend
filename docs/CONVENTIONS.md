# STEG Platform Backend — Engineering & Architectural Conventions

This document defines the strict engineering guidelines, package architecture, naming standards, error response formats, and mandatory pre-implementation checklists for the **STEG Smart Internship & Administrative Workflow Platform** backend.

---

## 1. Clean Architecture & Layer Responsibilities

The backend is structured as a **Modular Monolith** organized by business bounded contexts. Every business module under `tn.steg.backend.<module>` must adhere to the four concentric Clean Architecture layers:

```
tn.steg.backend.<module>
├── domain/            <-- Pure business entities, aggregates, domain events, domain services, repository port interfaces.
│                          ZERO framework dependencies (no Spring MVC, no Hibernate/JPA on business logic).
├── application/       <-- Use cases, input/output port definitions, commands, queries, application DTOs.
│                          Orchestrates domain models and transactions.
├── infrastructure/    <-- Outbound adapters: Spring Data JPA implementations, entity mappers, Flyway migrations,
│                          external API adapters (file storage, email, AI clients).
└── interfaces/        <-- Inbound adapters: Spring MVC @RestController, STOMP WebSocket handlers, request/response DTOs,
                           OpenAPI documentation annotations.
```

### The Inward Dependency Rule
- `domain` depends on **nothing**. It contains pure Java logic and value objects.
- `application` depends **only on `domain`**.
- `infrastructure` depends on `domain` (to implement repository ports) and `application`.
- `interfaces` depends on `application` (to invoke use cases) and `domain` (for data structures).
- **ArchUnit Enforcement:** Any circular dependency or layer violation automatically fails the build via `CleanArchitectureFitnessTest`.

---

## 2. Business Modules

The platform is partitioned into the following bounded contexts:
1. `iam`: Users, roles, permissions, JWT tokens, sessions, password policies, account lockouts.
2. `organization`: Departments (hierarchical), employees, supervisor profiles.
3. `candidate`: Candidate profiles, academic degrees, national ID (CIN) encryption.
4. `application`: Internship application lifecycle (DRAFT → SUBMITTED → UNDER_REVIEW → ACCEPTED / REJECTED).
5. `internship`: Accepted internships, duration tracking, deterministic classification.
6. `assignment`: Internship department & supervisor assignments (at most 1 ACTIVE assignment).
7. `workflow`: Configurable workflow definitions, step definitions, instances, and immutable action audit trails.
8. `companion`: Day-to-day intern execution: tasks/TODOs, daily journal entries, versioned deliverables.
9. `evaluation`: Supervisor evaluations linking directly to daily/weekly tasks and optional template criteria.
10. `document`: Logical documents, versioning, FileAsset physical storage abstraction, MIME/checksum verification.
11. `messaging`: Private intern ↔ supervisor channels and group chats with monotonic sequence ordering.
12. `finance`: FinanceCase dossiers, deterministic 50 TND/month payment calculations, approvals, and receipts.
13. `certificate`: Server-generated internship certificates with immutable timestamps and official STEG branding.
14. `notification`: Cross-cutting event-driven multi-channel notification dispatcher (IN_APP, EMAIL, PUSH).
15. `ai`: Strictly advisory AI assistants (document consistency checks, logbook drafting). CIN excluded.
16. `audit`: Immutable audit log capturing actor, action, entity snapshots, and IP addresses.
17. `reporting`: Read-only operational projections and metrics for Back Office dashboards.
18. `common`: Shared kernel containing base JPA entities, custom exceptions, error envelopes, and global configurations.

---

## 3. Naming & Coding Standards

| Concept | Convention | Example |
| :--- | :--- | :--- |
| Package names | Lowercase, singular | `tn.steg.backend.internship.domain` |
| JPA Entities | PascalCase, singular, suffix `JpaEntity` or domain name | `InternshipJpaEntity`, `Candidate` |
| Domain Services | PascalCase, suffix `Service` or `Policy` | `InternshipClassificationService`, `PaymentCalculationPolicy` |
| Use Cases | PascalCase, verb-first, suffix `UseCase` | `SubmitApplicationUseCase`, `ApprovePaymentUseCase` |
| Repositories (Port) | PascalCase, suffix `Repository` (interface in domain) | `InternshipRepository` |
| Repositories (Adapter) | PascalCase, suffix `JpaRepository` (in infrastructure) | `SpringDataInternshipRepository` |
| Controllers | PascalCase, suffix `Controller` | `InternshipController` |
| REST DTOs | PascalCase, suffix `Request` / `Response` | `CreateApplicationRequest`, `ApplicationResponse` |
| Flyway Migrations | `V<version>__<description>.sql` | `V2__iam.sql`, `V3__organization.sql` |

---

## 4. Standard Error Response Envelope

All REST API endpoints produce a consistent error envelope on failure:

```json
{
  "timestamp": "2026-09-03T14:30:00Z",
  "status": 400,
  "error": "Validation Failure",
  "message": "Input payload failed validation constraints.",
  "path": "/api/applications",
  "traceId": "d8e3b7c2-9a1f-4b05-b1a7-5e6f8c9d0e1f",
  "fieldErrors": [
    {
      "field": "desiredStartDate",
      "rejectedValue": "2026-01-01",
      "message": "Start date cannot be in the past"
    }
  ]
}
```

### HTTP Status Code Mapping
- `400 Bad Request`: Malformed JSON or syntax errors.
- `401 Unauthorized`: Missing or invalid JWT access token.
- `403 Forbidden`: Authenticated user lacks the required RBAC role or permission.
- `404 Not Found`: Target domain entity does not exist (`ResourceNotFoundException`).
- `409 Conflict`: Optimistic locking collision (`OptimisticLockingFailureException`) or unique constraint violation.
- `422 Unprocessable Entity`: Business rule or state transition violation (`BusinessRuleException`).
- `500 Internal Server Error`: Unexpected runtime exception.

---

## 5. Mandatory "Before Implementing a Feature" Checklist

Before writing any new endpoint or business logic, engineers and AI agents must systematically execute this 12-step checklist:

1. **Business Concept Identification:** Which aggregate or entity in `STEG_Master_Domain_Class_Diagram_v4.mmd` owns this data?
2. **Actor & Authorization:** Who initiates this action? What role is required (`CANDIDATE`, `SUPERVISOR`, `HR`, `FINANCE`, `ADMIN`)?
3. **Single Source of Truth:** Ensure business math (duration calculation, payment allowance) lives strictly in the backend domain layer.
4. **Domain Rule & Invariants:** What state preconditions must be satisfied before the state transition is permitted?
5. **Persistence & Schema:** Are unique indexes, foreign key cascades, and check constraints defined in Flyway?
6. **Application Orchestration:** Implement the use case in `application/` without web-tier dependencies.
7. **API Contract:** Design the REST endpoint in `interfaces/` with Bean Validation (`@Valid`) and OpenAPI annotations.
8. **Client Compatibility:** Verify compatibility with Front Office (Next.js), Back Office (Angular), and Mobile (Flutter).
9. **Audit Trail:** If the action mutates sensitive data, is it recorded in `AuditLog`?
10. **Data Privacy & Security:** If CIN or national ID is involved, is it encrypted/hashed and excluded from AI analysis?
11. **Testing:** Write unit tests for domain services, use case tests, and Testcontainers integration tests.
12. **Unresolved Assumptions:** If a requirement is not confirmed by STEG, flag it as `TODO — STEG VALIDATION REQUIRED` instead of inventing logic.
