# Phase A14 — Backend Hardening & Release Readiness

> **Status:** ✅ Complete (2026-09-06 — full suite green: 298 tests, 0 failures)
> **Date:** 2026-09-06
> **Scope:** security review, performance pass (N+1 elimination with query-count
> gates), containerization (validated image + compose stack), CI pipeline,
> migration review, backup/secrets runbook, and the client-facing API overview.
> No business rules were changed; one real IDOR was found and fixed.

---

## 1. Security review (task 1)

| Check | Result |
|---|---|
| Explicit authorization on all endpoints | PASS — `MethodSecurityFitnessTest` (deny-by-default ArchUnit gate) green; every `@RestController` method carries `@PreAuthorize`/`@Secured`/`@RolesAllowed` or `@PublicEndpoint`. Runtime counterpart added: `PublicEndpointConsistencyTest` (3/3) proves register/login/refresh/universities answer anonymously (400 on bad bodies, not 401) while audit/logout/finance-cases demand credentials. |
| No secrets committed | PASS with triaged fixtures — `git grep` over tracked files finds no private keys, tokens, or passwords. Secret-shaped committed values: the **test-profile-only** JWT key (`src/test/resources/application-test.yml`, ephemeral testcontainers DBs) and dummy `application-local.yml.example` values. A post-push GitGuardian alert on demo defaults in `docker-compose.yml` (`stegdemo` DB password, placeholder JWT secret) was remediated the same day: secrets are now **required with no defaults** (`${VAR:?…}` fail-fast), and a placeholder-only `.env.example` is tracked (real `.env` stays git-ignored). Production fails fast without `STEG_JWT_SECRET`, so no fallback exists anywhere. |
| Dependency vulnerability scan | EXECUTED via OSV (Maven Central unreachable from this host, so the OWASP plugin binary could not be downloaded; NVD API reachable). All 286 resolved artifacts queried: **2 packages / 4 advisories**, both triaged below. CI runs dependency-check gated on CVSS ≥ 7 once `NVD_API_KEY` is provisioned; Dependabot added for automated bumps. |
| Password/token handling | PASS (reviewed, unchanged): BCrypt hashing, refresh tokens stored as hashes only and rotated per use, 10/min/IP login gate, 5-strike lockout, HS512 access tokens, auth failure messages static (no user enumeration beyond invalid-credentials). |
| Cross-module IDOR | **1 real finding fixed** (see §2) + fresh `CrossModuleIdorTest` (3/3) for internships, evaluations, finance cases. Pre-existing guards re-verified intact: applications, messaging, documents, candidates, certificates. |

### Vulnerability triage (OSV, 2026-09-06)

- `tomcat-embed-core@11.0.24` (via Boot 4.1.1 BOM): GHSA-9xv2-5v5q-p794 (CVSS 9.8,
  DIGEST-auth capture-replay), GHSA-gcx9-497g-6cp6 (constraint bypass),
  GHSA-h3x4-894j-xpx5 (FORM-auth bypass). Fixed in 11.0.25. **Not reachable here:**
  no Tomcat Realm/valve/`login-config` exists in the app — authentication is
  exclusively the Spring Security JWT filter; container-managed auth is never
  configured. Remediation path: Spring Boot patch release (Dependabot will flag).
- `jsoup@1.22.2` (transitive via spring-ai readers): GHSA-pmhh-3w7g-xqp8 (CVSS 6.1,
  XSS only with custom Safelists permitting raw-text elements). **Not reachable:**
  no `Cleaner`/`Safelist`/`Jsoup` usage in app code. Remediation: bump to ≥1.23.1
  (Dependabot).
- Local `.m2` holds no fixed versions and Maven Central is unreachable from this
  host, so the bumps are queued for CI/networked environments — documented, not
  silently dropped.

### IDOR fix (the one failing check)

`GET /api/internships/{id}` and `GET /api/internships/{id}/classification` used a
role-only gate (`CANDIDATE` allowed) with no ownership check, so any candidate could
read any other candidate's internship including `candidateFullName` (proven: test
failed with `200` pre-fix, `403` post-fix). Fixed with
`@PreAuthorize("@authz.hasAnyRole('ADMIN','HR','SUPERVISOR') or @authz.isInternOf(#id)")`
— staff keep wide read (consistent with the staff-only list), candidates read only
their own. Certificate download, candidate profiles, companion, evaluation, finance,
messaging, and document endpoints were each re-verified scoped (service-level
ownership or participant checks) — no further findings.

## 2. Performance pass (task 2)

Measured hot-path costs after the fix (Hibernate statistics, production-like cold
persistence context; `findById` entity loads are not counted by the statistic —
only executed HQL/JPQL queries are):

| Hot path (rows seeded) | Before | After (measured) | Gate |
|---|---|---|---|
| Staff application list (5) | 1+N(+N reviewer) | **1** | ≤ 2 |
| Candidate application list (3) | full candidate table scan + N | **2** (`findByUserId` + list) | ≤ 3 |
| Internship list (3) | 1+2N | **1** | ≤ 2 |
| Assignment history (4) | 2+3N | **1** (+1 uncounted `findById`) | ≤ 3 |
| Conversation history page (10) | ~2P+5 | **6, flat** | ≤ 10 |
| Finance case list page (3) | ~6P+2 | **6** (7 on full pages incl. count) | ≤ 12 |

Fixes (all additive, behavior-preserving): fetch-join list queries
(applications, internships, assignments, messages+sender, finance page+internship);
bulk `IN` preloads for finance children (calculations, dossier+document+reviewer,
approvals+decider, receipts, workflow instance ids) and message attachments+file;
candidate list uses indexed `findByUserId` instead of scanning the table.
`HotPathQueryCountTest` (7/7, incl. a V25 index-presence check) locks these bounds.

**V25__hot_path_indexes.sql** (additive): `idx_wf_inst_finance_case_id` (required by
the new workflow bulk query) and `idx_assignments_internship_status` (composite for
the active-assignment lookups gating nearly every membership check). All other hot
FKs were already indexed (verified against V1–V24).

**Load baseline** (`loadtest/k6-baseline.js` + `seed.sql`, k6 v2.2.0, compose stack,
near-empty DB): 1333 requests, **0 failures**, avg 6.5 ms / p50 5.3 ms /
p90 9.7 ms / **p95 10.9 ms** / max 96 ms across login, application list, internship
list, audit page, notifications page, universities (10 API VUs + 2 auth VUs, 60 s).
Auth scenario stays under the login gate by design. Full numbers: `loadtest/BASELINE.md`.

## 3. Containerization (task 3) — validated, not just written

- `Dockerfile` (multi-stage: Maven 3.9 + Temurin 21 build → slim `eclipse-temurin:21-jre-jammy`
  runtime, non-root user, curl healthcheck on public `/actuator/health`): **built
  successfully** (`steg-backend:local`).
- `docker-compose.yml` (backend + `postgres:17-alpine`, `pg_isready` + actuator
  healthchecks, named volumes, env-driven config with fail-fast required secrets): `config`
  valid, **stack reached `healthy/healthy`**, Flyway migrated V1–V25 on the fresh
  volume, and smoke register→login→authenticated calls passed against the
  prod-profile container. k6 baselines above ran against this stack.

## 4. CI pipeline (task 4)

`.github/workflows/backend-ci.yml` (in the `steg-backend` repo — the only git
remote): `build-test` (`./mvnw -B clean verify` on Java 21 + Docker for
Testcontainers, uploads surefire + `openapi.json` artifacts), `dependency-check`
(OWASP, fails on CVSS ≥ 7, skips gracefully until `NVD_API_KEY` is provisioned),
`publish` (GHCR image on `main` after green tests). Plus `.github/dependabot.yml`
(maven/docker/actions, security-grouped). **Validated:** actionlint clean; every
CI step except the hosted runner itself was executed locally with the same
commands (full suite 298/0, image build, k6). "CI green end-to-end" therefore
means: pipeline definition lint-clean and all its steps locally green — the first
GitHub run will confirm the hosted side.

## 5. Migration review (task 5)

- V1–V25 applied with `success=true` on a **fresh** PostgreSQL 17 (compose volume
  created today — see `flyway_schema_history`), proving CI-fresh-DB safety.
- Shipped migrations untouched: `git status` shows zero modified `*.sql` files;
  V25 is purely additive (`CREATE INDEX IF NOT EXISTS`).
- No `DROP`/`TRUNCATE`/`DELETE` and no non-transactional `CONCURRENTLY` anywhere
  in V1–V25 — existing databases upgrade by applying only the new version(s).

## 6. Runbook & secrets (task 6)

`docs/DEPLOYMENT.md`: full env/secrets checklist (4 required vars incl. fail-fast
`STEG_JWT_SECRET`; safe defaults table; committed-fixture triage), compose deploy
+ rollback procedure (additive migrations ⇒ app rollback never needs schema
rollback), `pg_dump --format=custom` backup + storage-volume snapshot + empty-DB
restore drill with retention guidance, and post-deploy monitoring (health groups,
ADMIN-gated prometheus, traceId log alerts, Flyway failure rows).

## 7. API overview (task 7)

`docs/API_OVERVIEW.md`: auth/security model, error envelope, rate limits,
pagination/cursor rules, all 18 tags / 103 operations (generated from the real
`openapi.json`), STOMP summary, and six client integration rules. Written for
Parts B/C/D onboarding.

---

## Test run & A14 inventory (new tests: 13)

```
./mvnw clean test --no-transfer-progress
Tests run: 298, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| Test | Count | Guards |
|---|---|---|
| `HotPathQueryCountTest` | 7 | per-hot-path SQL bounds + V25 index presence |
| `CrossModuleIdorTest` | 3 | internship / evaluation / finance cross-tenant denial (+ own-access positive) |
| `PublicEndpointConsistencyTest` | 3 | anonymous surface == security config |
| A13 and earlier | 285 | unchanged, all green |

Reconciliation: 285 (A13 final) + 13 (new A14 methods, each verified executing in
its surefire report) = 298 (exact `<testcase>` total). Only pre-existing test file
touched: `StegBackendApplicationTests` (V24→V25 expectation), still 2 tests.

PASS: all seven A14 tasks incl. validated container/CI/load artifacts; full suite
298/0; migrations V1–V25 clean on fresh DB; `docs/openapi.json` untouched by A14
(no contract change — only `@PreAuthorize` values).
PENDING / accepted: Tomcat 11.0.25+ and jsoup 1.23.1+ bumps queued for a
networked environment (unreachable paths documented, Dependabot wired); OWASP
plugin run deferred to CI (OSV scan done locally instead); `@Nested`
surefire-txt quirk and unknown-path 5xx unchanged (pre-existing, documented in A13).

---

## Key files

```
Dockerfile / docker-compose.yml            # validated image + demo stack
.github/workflows/backend-ci.yml           # lint-clean CI (build/test/scan/publish)
.github/dependabot.yml                     # automated bumps incl. security fixes
loadtest/{k6-baseline.js,seed.sql,BASELINE.md}
src/main/resources/db/migration/V25__hot_path_indexes.sql
src/test/.../performance/HotPathQueryCountTest.java
src/test/.../security/{CrossModuleIdorTest,PublicEndpointConsistencyTest}.java
docs/{DEPLOYMENT.md,API_OVERVIEW.md,PHASE_A14_HARDENING.md(this file)}
```
