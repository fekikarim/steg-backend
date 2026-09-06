# k6 baseline — backend top-5 endpoints

Measured 2026-09-06 with `k6 run loadtest/k6-baseline.js` (k6 v2.2.0) against the
local compose stack (`docker compose up -d --build`, seeded via `seed.sql`).

Environment (so future runs are comparable, not identical): Apple Silicon laptop,
Docker Desktop, backend container (JRE 21, `MaxRAMPercentage=75`), PostgreSQL
17-alpine container, near-empty database (1 admin, 1 university, a handful of
candidates/applications from setup runs).

## Load shape

- `auth` scenario: 2 VUs × 60s, re-login every ~25–35s (stays under the
  10/min/IP login gate by design).
- `api` scenario: 10 VUs × 60s round-robin over the 5 endpoints (~0.2–0.7s think
  time between iterations).

## Results (2nd full run)

| Metric | Value |
|---|---|
| Total requests | 1333 |
| Failed requests | 0 (0.00%) |
| Failed checks | 0 |
| avg latency | 6.48 ms |
| med (p50) | 5.28 ms |
| p90 | 9.65 ms |
| p95 | 10.93 ms |
| max | 95.89 ms |
| Throughput | ~14.7 req/s (12 VUs, think time included) |

Thresholds enforced by the script: `http_req_failed rate<0.01`,
`http_req_duration p(95)<1000` — both passed with large headroom.

## Reading these numbers

- This is a **smoke/baseline**, not a stress test: it proves the five hottest
  reads (login, application list, internship list, audit page, notifications
  page, plus public universities) serve in single-digit milliseconds on a warm
  local stack with zero errors — including the A14 N+1 fixes above.
- Re-run after any hot-path change and compare p95; investigate if p95 exceeds
  1000 ms or any check fails. For capacity planning, raise VUs/duration and
  remove think time rather than trusting these absolute figures.
