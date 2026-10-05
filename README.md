# STEG Backend

Spring Boot 4.1.1 modular monolith (`tn.steg.backend.<context>/{application,domain,infrastructure,interfaces}`),
Java 21, Postgres 17 via Flyway (`src/main/resources/db/migration`, currently V49).
Business rules, authorization, scoping and state transitions live here — the
Angular/Next/Flutter clients only render and call the API.

## Run

```bash
# 1. Configure (never commit secrets — see .env.example)
cp steg-backend/.env.example .env   # then fill SPRING_DATASOURCE_PASSWORD + STEG_JWT_SECRET

# 2. Full stack (postgres + backend + python-ai) from the repo root
docker compose up -d --build
# Backend :8080, python-ai :8000. Health: /actuator/health
```

Seeded demo data is OFF by default. For a seeded integration run only:

```bash
SPRING_PROFILES_ACTIVE=integration STEG_SEED_DEMO_ENABLED=true \
STEG_SEED_DEMO_PASSWORD=<you-chose-it> docker compose up -d --build
```

(The root compose does not forward `STEG_SEED_DEMO_*`; use
`docker-compose.smoke-tmp.yml`-style overlay or export them. The seeder fails
fast when enabled without an explicit password.)

## Test

```bash
cd steg-backend
./mvnw clean test        # 780+ tests incl. ArchUnit + Testcontainers (needs Docker)
```

Cross-client gates (repo root): `npm test` + `npm run build` in
`steg-back-office/` (449+ specs, strict TS), `flutter test` + `flutter analyze`
in `stegappe/`, `pytest ai-services`, `tsc --noEmit` + `next build` in
`steg-front-office/`. Smoke: `./scripts/smoke-test.sh` against a live stack
(steps 7–9 use the explicit statuses: atomic approve, `APPROVED →
IN_PROGRESS`, supervisor link).

## Configure (`.env` — see `.env.example` for the full reference)

| Area | Variables | Behavior when empty/missing |
|---|---|---|
| Gemini (chatbot + AI task drafts, backend only) | `GEMINI_API_KEY` (fallback `AI_API_KEY`), `GEMINI_MODEL` (override `AI_MODEL`), `AI_BASE_URL`, `AI_TIMEOUT_MS`, `AI_MAX_OUTPUT_TOKENS`, `AI_TEMPERATURE` | No hard-coded default: blank key/model degrades to `AI_UNAVAILABLE` (503 on generation, friendly FR fallback on chatbot). Core flows keep working. |
| python-ai document verification | `PYTHON_AI_SERVICE_BASE_URL`, `PYTHON_AI_SERVICE_TOKEN` (sent as `X-Service-Token`), `PYTHON_AI_TIMEOUT_MS`, `PYTHON_AI_MAX_ATTEMPTS`, `PYTHON_AI_CIRCUIT_THRESHOLD` | Unreachable/misconfigured → `INCONCLUSIVE` degraded runs; manual validation and receipts still work. |
| Email / Brevo (Resend REST) | `RESEND_API_KEY`, `NOTIFICATIONS_MAIL_ENABLED`, `NOTIFICATIONS_MAIL_FROM` | Approval/account flows commit anyway with `emailSent: false`; resend mints a NEW password. |
| Time zone | `APP_TIMEZONE` (default `Africa/Tunis`, read as `steg.timezone`) | Every calendar-day filter (candidates, audit, notifications) resolves bounds in this zone; instants stay UTC. Unknown zone fails fast. |
| Demo seeder | `STEG_SEED_DEMO_ENABLED` (default `false`), `STEG_SEED_DEMO_PASSWORD` (no default) | Seeding runs only on the `integration` profile with the explicit switch; empty password fails fast. |
| CORS | `CORS_ALLOWED_ORIGINS` (default `http://localhost:3000,http://localhost:4200`) | Only the two front ends; credentials allowed, `X-Trace-Id` exposed. |
| Uploads | baked in: 25 MB servlet cap, PDF magic-bytes + Tika validation, malware scan | Oversize/spoofed/empty uploads are refused (`EMPTY_FILE`, `SPEC_PDF_*`, `MALWARE_DETECTED`); downloads are owner-or-staff authorized. |
