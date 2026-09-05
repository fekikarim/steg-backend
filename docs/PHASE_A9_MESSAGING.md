# Phase A9 — Real-Time Messaging (WebSocket) — Implementation Record

Status: **COMPLETE** (production-readiness gate passed, `./mvnw clean verify` green).

## 1. Implemented behavior

- **Private threads**: exactly intern + currently ACTIVE supervisor per internship,
  auto-created/reused on every ACTIVE assignment (`InternshipService.assign` →
  `MessagingService.ensurePrivateThread`). Reassignment soft-removes the stale
  supervisor (`leftAt` set, history preserved) and activates the new one; the
  thread is never duplicated (partial unique index `uq_private_thread_per_internship`).
- **Group conversations**: scoped to users with a currently ACTIVE internship at
  add/create time; staff (ADMIN/HR/DIRECTOR) or group MODERATOR/OWNER manage members.
  Leaving sets `leftAt` (history preserved); rejoin retains watermarks so messages
  sent while away still count as unread.
- **Ordering**: monotonic `sequenceNumber` per conversation allocated under
  `SELECT ... FOR UPDATE` on the parent conversation plus defensive DB constraint
  `uq_messages_conv_seq` (V19). Verified gapless/duplicate-free under 20 parallel
  senders (`MessagingConcurrencyTest`).
- **Authorization**: every operation requires active (`leftAt IS NULL`) membership;
  unknown and non-member ids are indistinguishable (403/404, no leakage). IDOR
  guessing is covered by tests. `@authz.isOwnConversation` is membership-backed
  (ADMIN bypass only).
- **Transports**: STOMP `/ws` (plain + SockJS) with JWT handshake + per-frame auth;
  REST fallback for history/send/acks/attachments. Every mutation on either
  transport broadcasts to `/topic/conversations/{id}` (best-effort; persistence
  stays authoritative, clients converge via history).

## 2. Lifecycle SENT → DELIVERED → READ (final decision)

Single `status` column; delivery/read evidence additionally lives in per-member
sequence watermarks:

| Status | Meaning |
|---|---|
| `SENT` | Persisted, not yet delivered to any non-sender active member. |
| `DELIVERED` | Observed by ≥1 non-sender active member (explicit `/delivered` ack, STOMP ack, or history fetch which auto-acks). |
| `READ` | **All** non-sender active members have `lastReadSequenceNumber >= seq`. |
| `EDITED` | Terminal display state after sender edit (watermarks preserved as evidence). |
| `DELETED` | Soft-delete: content redacted (`[message deleted]`), attachments hidden, row + files kept for audit. |

Persistence (server stored) ≠ delivery ack (recipient observed) ≠ read ack
(recipient read). `markRead` advances both watermarks (reading implies delivery)
and is monotonic (rewind rejected, equal is idempotent). History fetch advances
only the delivery watermark (implicit, unaudited to avoid audit spam; explicit
acks are audited as `MESSAGE_DELIVERED` / `MESSAGE_READ`).

## 3. Read watermark (final decision)

`conversation_members.last_read_sequence_number` is authoritative;
`last_delivered_sequence_number` tracks delivery; `last_read_at` is a
wall-clock audit marker only (V20). Unread =
`count(seq > watermark AND sender != viewer)` — own messages never count.
The former timestamp approximation and its `TODO — STEG VALIDATION REQUIRED`
are removed.

## 4. Endpoint matrix

REST (`/api/conversations`): `GET /` · `POST /group` · `GET /{id}` ·
`POST /{id}/members` · `POST /{id}/leave` · `GET /{id}/messages?cursor=` ·
`POST /{id}/messages` · `POST /{id}/messages/with-attachment` ·
`PATCH /messages/{id}` (sender only) · `DELETE /messages/{id}` (sender/staff,
soft) · `POST /{id}/delivered` · `POST /{id}/read` · `GET /unread/counts` ·
`GET /messages/{id}/attachments` · `GET /attachments/{id}/download` (streamed,
audited).

STOMP: `/app/conversations/{id}/send` · `/app/conversations/{id}/delivered` ·
`/app/conversations/{id}/read` → broadcasts on `/topic/conversations/{id}`,
errors on `/user/queue/errors`.

## 5. Attachment security review (outcome)

| Control | State |
|---|---|
| MIME allow-list (`OTHER`: PDF/JPEG/PNG via Tika magic bytes, spoof-checked) | Enforced pre-storage; executables rejected (`INVALID_FILE_TYPE`) |
| Size cap (10 MB default) | Enforced pre-storage (`FILE_TOO_LARGE`) |
| Private storage (opaque UUID subfolder outside webroot, no public URL) | Yes (`LocalFileStorageService`) |
| Membership authorization (upload/list/download) | Yes, per operation; deleted-message attachments blocked |
| Malware scanning | **NoOp hook only (always clean)** — NOT production-safe (see §7) |
| Atomicity (message + file in one tx; validation failure rolls back) | Yes (`BusinessRuleException` is unchecked) |
| Deletion | Files retained on soft-delete (audit); hidden from reads |
| Access auditing | `MESSAGE_ATTACHMENT_ADDED` on upload, `MESSAGE_ATTACHMENT_DOWNLOADED` (actor + IP) on download |

## 6. JWT over WebSocket (final decision)

Clients MUST prefer `Authorization: Bearer` (STOMP CONNECT native header, or HTTP
header on upgrade). `?token=` exists only for header-less upgrade environments.
Handshake rejection logging uses the sanitized path only — token values never
reach logs (covered by `JwtHandshakeInterceptorTest`). Deployments MUST use
`wss`, short-lived tokens, and access-log formats excluding query strings.

## 7. Known limitations (do not expand scope without validation)

1. **NoOp malware scanner**: wire a real ClamAV/VirusTotal bean (named
   `customMalwareScanner`) before accepting untrusted uploads in production.
2. Storage keys embed the sanitized original filename (may carry PII); consider
   UUID filenames if STEG requires it.
3. Unread excludes own messages by design; `EDITED`/`DELETED` are terminal
   display states (watermarks still prove delivery/read).
4. GROUP intern-scope is enforced at add/create time, not continuously:
   completed interns keep history but cannot join new groups (staff should
   prune groups on completion if STEG policy requires it).
5. Delivery/read broadcasts are per-message frames; very large ack ranges fan
   out proportionally (acceptable for MVP volumes).
6. Private-thread leave is permitted (history preserved); next assignment
   re-adds current participants.

## 8. Verification

`MessagingIntegrationTest` (17: lifecycle, watermarks, unread, rewind/range
negatives, attachment matrix, rejoin, reassignment, completion, negative batch),
`MessagingConcurrencyTest` (20 parallel senders, gapless 1..20),
`JwtHandshakeInterceptorTest` (3), `MessagingWebSocketIntegrationTest`
(3: round-trip, STOMP acks, REST→WS broadcasts).
Full `./mvnw clean verify`: **126 tests, 0 failures, 0 errors, 0 skipped —
BUILD SUCCESS** (includes ArchUnit clean-architecture + deny-by-default gates).
