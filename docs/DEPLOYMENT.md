# STEG Backend — Deployment Runbook (Phase A14)

Target: a production-grade deployment of `steg-backend` + PostgreSQL 17.
Local/demo equivalent: `docker compose up -d --build` (see `docker-compose.yml`).

---

## 1. Environment variable / secrets checklist

Only `STEG_JWT_SECRET` has no default — the application **fails fast at startup**
if it is unset, so a missing production secret can never silently fall back to a
committed value. Everything else below falls back to the shown default.

### Required (no default — must be provided)

| Variable | Used as | Requirement |
|---|---|---|
| `STEG_JWT_SECRET` | `steg.security.jwt.secret-key` | High-entropy 256-bit+ string (HS512 signs with the full byte length; 64+ random chars minimum). Inject via secret management, never commit. |
| `SPRING_DATASOURCE_URL` | JDBC URL | e.g. `jdbc:postgresql://postgres:5432/stegdb` |
| `SPRING_DATASOURCE_USERNAME` | DB user | Least-privilege role owning the schema |
| `SPRING_DATASOURCE_PASSWORD` | DB password | Secret management, never commit |

### Security/behavior tuning (have safe defaults)

| Variable | Default | Notes |
|---|---|---|
| `JWT_ACCESS_EXP_MINUTES` | `15` | Short-lived access tokens |
| `JWT_REFRESH_EXP_DAYS` | `7` | Rotated server-side on every use |
| `LOCKOUT_MAX_ATTEMPTS` / `LOCKOUT_DURATION_MINUTES` | `5` / `30` | Brute-force lockout |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:4200` | **Must be set to the real front/back-office origins in production** |
| `MALWARE_MODE` | `noop` | **Set `clamav` in production**; `noop` accepts unscanned uploads (dev only). With `clamav`: `CLAMAV_HOST`/`CLAMAV_PORT` (default `127.0.0.1:3310`), `MALWARE_ON_ERROR` stays `reject` (fail-closed) |
| `AI_API_KEY` (or `GEMINI_API_KEY`) | empty (AI disabled) | Absence only degrades AI features gracefully; never required for boot |
| `STORAGE_PROVIDER` / `STORAGE_LOCAL_ROOT` | `local` / `./storage/uploads` | Mount persistent storage at the root path; back it up (see §3) |
| `SMTP_HOST/PORT/USERNAME/PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS` | localhost:1025, no auth | Only used when `steg.notifications.mail.enabled=true` |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` | driver defaults | Size for expected concurrency (see `loadtest/BASELINE.md`) |
| Finance math (`steg.finance.*`) | rate 50 TND, cap 150 TND, max 3 months | Constants, not secrets; changing them alters payment outcomes — treat as config, review with Finance |

Committed fixtures that are **not** production secrets (documented, test/dev only):
`src/test/resources/application-test.yml` JWT key (test profile, ephemeral testcontainers
DBs) and `application-local.yml.example` dummy values. The local `.env` file is
git-ignored and never committed.

---

## 2. Deploy steps (compose-based host)

1. Provision host with Docker; create a dedicated data volume (compose `pgdata`).
2. Provide secrets (`STEG_JWT_SECRET`, DB credentials, SMTP, `GEMINI_API_KEY` if used)
   via environment or a secrets manager — never in the image.
3. Set `CORS_ALLOWED_ORIGINS` to the real client origins; set `MALWARE_MODE=clamav`
   with a reachable clamd (or accept and document the `noop` risk window).
4. `docker compose up -d --build`. Flyway migrates automatically on backend boot
   (`baseline-on-migrate: true`); the backend `healthcheck` gates readiness.
5. Verify: `curl -f http://<host>:8080/actuator/health` → `{"status":"UP"}`;
   `GET /v3/api-docs` serves the contract; smoke register→login→authenticated GET.
6. Rollback: re-deploy the previous image tag (`ghcr.io/<org>/steg-backend:<sha>`);
   migrations are additive-only (V1→V25 contain no DROP/DELETE), so rolling the
   application back never requires rolling the schema back.

---

## 3. PostgreSQL backup / restore runbook

Assumes the compose service names (`postgres` container, `stegdb` database).
Run from the compose host. Stop-the-world is **not** required (`pg_dump` is
consistent via a single transaction).

```bash
# --- Backup (nightly cron recommended; AES-encrypt + offsite the artifact) ---
docker compose exec -T postgres \
  pg_dump -U "$SPRING_DATASOURCE_USERNAME" -d "$POSTGRES_DB" \
    --format=custom --compress=9 --file=/tmp/stegdb-$(date +%F).dump
docker cp steg-postgres:/tmp/stegdb-$(date +%F).dump /backups/

# File uploads live OUTSIDE the database — snapshot the storage volume too:
docker run --rm -v steg-backend_storage:/data -v /backups:/out alpine \
  tar -czf /out/storage-$(date +%F).tar.gz -C /data .

# --- Restore (to an empty database; verifies backup integrity) ---
docker compose exec -T postgres psql -U "$SPRING_DATASOURCE_USERNAME" -d postgres \
  -c "DROP DATABASE IF EXISTS stegdb_restore; CREATE DATABASE stegdb_restore;"
docker cp /backups/stegdb-<date>.dump steg-postgres:/tmp/restore.dump
docker compose exec -T postgres \
  pg_restore -U "$SPRING_DATASOURCE_USERNAME" -d stegdb_restore /tmp/restore.dump
# Point a staging backend at stegdb_restore and smoke-test before promoting.
```

Restore drills: perform the empty-DB restore above at least once per release and
keep the last 30 daily dumps (retention per STEG policy).

---

## 4. Post-deploy health to monitor

- `GET /actuator/health` (liveness/readiness groups) and `GET /actuator/prometheus`
  (ADMIN-gated; scrape `http_server_requests_seconds`, `jvm_*`, `hikaricp_*`).
- Structured JSON logs carry `traceId` per request — alert on 5xx rate and on
  `Rate Limit Exceeded` bursts (abuse signal).
- Flyway `flyway_schema_history.success = false` rows mean a failed migration:
  fix forward with a new additive migration, never edit a shipped one.
