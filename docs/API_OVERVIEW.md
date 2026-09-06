# STEG Backend — API Overview (Phase A14)

Onboarding guide for the Front Office, Back Office, and Mobile teams.
Authoritative contract: [`openapi.json`](./openapi.json) (OpenAPI 3.1.0, 103 paths —
generate clients from it, don't hand-roll calls). Runtime docs:
`/swagger-ui.html`, `/v3/api-docs`. Deployment: [`DEPLOYMENT.md`](./DEPLOYMENT.md).

Base URL (local/demo): `http://localhost:8080`. All JSON uses UTF-8.

---

## 1. Authentication & security model

- **JWT access token** (15 min default) in `Authorization: Bearer <token>` +
  **rotating opaque refresh tokens** (`POST /api/auth/refresh`; old token revoked,
  sessions tracked with IP/user-agent). `POST /api/auth/logout(-all)` revokes.
- **Self-registration is candidates-only** (`POST /api/auth/register`); staff
  accounts are provisioned by admins. Login is rate-limited (10/min/IP) and
  accounts lock after 5 bad passwords (30 min).
- **Deny-by-default:** every endpoint carries an explicit authorization rule
  (ArchUnit-enforced in CI). Only four operations are public: `register`, `login`,
  `refresh`, and `GET /api/universities` (registration reference data).
- **Roles:** `CANDIDATE`, `INTERN`, `SUPERVISOR`, `HR`, `FINANCE`, `DIRECTOR`,
  `ADMIN`, plus the fine-grained permission `DOCUMENT_VIEW_RESTRICTED`.
- **Ownership rules clients must mirror (backend re-enforces everything):**
  - Candidates see only their own profile/applications/internship/evaluations;
    interns see only internships they participate in; supervisors only internships
    they actively supervise (`isSupervisorOf`/`isInternOf`/`isParticipantOf`).
  - Restricted (CIN-bearing) documents download only with
    `DOCUMENT_VIEW_RESTRICTED` or `ADMIN`, from an allowed IP, and every access
    is audit-logged before bytes stream.
  - Finance approve/reject: `FINANCE` role only. Certificates: active supervisor
    of a COMPLETED internship (or ADMIN/HR); download additionally open to the
    owning intern. Audit viewer: `ADMIN` only. Metrics/prometheus: `ADMIN` only.
- **Standard error envelope** on every failure:
  `{timestamp, status, error, message, path, traceId, fieldErrors[]}`.
  Send back `traceId` when reporting issues; echo/forward `X-Trace-Id` for
  correlation. Notable codes: `400` validation, `401` auth, `403` forbidden,
  `404` not found, `409` concurrent modification, `422` business rule,
  `429` rate limit (with `Retry-After`).
- **Rate limits** (per user, sliding window): uploads 10/min, AI endpoints
  10–20/min. Login 10/min/IP. Back off on 429.
- **Pagination:** list endpoints accept `page/size/sort` and return Spring `Page`
  JSON (`content`, `totalElements`, `totalPages`, …). Message history is
  cursor-based on `sequenceNumber` (never rely on `sentAt` ordering).

## 2. Module map (18 tags, 103 operations)

| Tag | Prefix | What it does | Key roles |
|---|---|---|---|
| Authentication | `/api/auth/*` | register, login, refresh, logout(-all) | public + self |
| Candidates | `/api/candidates*`, `/api/universities` | own profile CRUD, staff lookup, public university list | CANDIDATE self; ADMIN/HR staff view |
| Applications | `/api/applications*` | DRAFT → SUBMITTED → review lifecycle, documents attach/verify | CANDIDATE own; ADMIN/HR review |
| Internships | `/api/internships*` | create (from application/manual), dates (= auto reclassification), assign/reassign, cancel, classification explainer | ADMIN/HR manage; candidates read own only |
| Workflows | `*/workflow*`, `/api/workflows/*` | governed state transitions + immutable action history | ADMIN/HR execute; candidates read own |
| Companion | `/api/internships/*/tasks|journal|deliverables` | intern day-to-day: tasks, journal submit→validate, versioned deliverables | intern own; supervisor validates own interns |
| Comments | `*/comments` | threads on journal entries, deliverables, evaluations | participants |
| Evaluations | `/api/evaluation-templates/*`, `/api/*/evaluations*` | HR-configured templates/criteria; supervisor scoring (server-computed totals) | HR/ADMIN templates; active supervisor scores |
| Documents | `/api/documents*`, `*/documents*` | upload (10/min), metadata, download, restricted download, verify | owner/staff; restricted needs permission+IP |
| Messaging | `/api/conversations*` + STOMP `/ws` | private/group conversations, sequence-ordered history, read/delivered watermarks, attachments | active members only (re-checked per send) |
| Notifications | `/api/notifications*` | own notifications, unread counts, mark read | self only |
| Finance | `/api/finance-cases*` | open (COMPLETED OBLIGATOIRE only) → dossier → recalculate → approve/reject → receipt PDF | FINANCE/ADMIN (+HR/supervisor receipt download) |
| Certificates | `/api/internships/*/certificates`, `/api/certificates/*` | server-generated PDF (official logo, server date), download | active supervisor; owner/staff download |
| AI Assistance | `/api/ai/*` | advisory analyses, logbook drafts, candidate Q&A, human review of recommendations (advisory only — never mutates state) | role + participant scoped; 10–20/min |
| Reports | `/api/reports/*` | read-only aggregates: applications/internships/finance by status, payment totals by period×department | HR/DIRECTOR/ADMIN; FINANCE for treasury |
| Audit | `/api/audit*` | paginated, filterable audit log | ADMIN only |
| Organization | `/api/departments*`, `/api/employees*` | org chart + staff CRUD (soft-delete) | ADMIN/HR |

Real-time: STOMP over `/ws` (JWT at handshake **and** per-message membership
re-checks); personal notifications on `/user/queue/notifications`; conversation
topics `/topic/conversations/{id}`. Full WS contract: `PHASE_A9_WS_CONTRACTS.md`.

## 3. Client integration rules

1. Never duplicate backend logic: classification, payment math, totals, and
   permissions are server-computed — render them verbatim.
2. Never trust client validation alone: map the envelope's `message`/`fieldErrors`.
3. Treat 401 as "re-authenticate" (silent refresh, else login screen); 403 as
   "hide/disable this UI for this role".
4. Uploads: pre-validate type/size client-side as UX only; the backend (Tika +
   malware hook + per-type caps) is the real gate.
5. AI outputs are drafts/advisory: label them as AI, never auto-publish, always
   require explicit human confirmation.
6. Poll notifications/message history with cursors (`sequenceNumber`), not timers
   on full-list fetches.
