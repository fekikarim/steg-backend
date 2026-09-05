# Phase A10 — Notifications — Implementation Record

Status: **COMPLETE** (`./mvnw clean verify`: **150 tests, 0 failures, 0 errors, 0 skipped**).

## 1. Architecture (anti-circularity as specified)

Business facts are `common.domain.event` records (`DomainEvent` sealed interface:
`eventId`, `occurredAt`, `actorId`; IDs + display strings only, never entities).
Business services publish via `ApplicationEventPublisher` and depend only on
`common`. `NotificationEventListener` (notification module) consumes them —
notification depends on events, never the reverse.

Handlers run `@TransactionalEventListener(BEFORE_COMMIT)`, joining the
publisher's transaction: fan-out commits atomically with the business fact (a
rollback can never leave a phantom alert), uses no extra JDBC connection, and
needs no async machinery. A separate commit-time transaction was load-tested
out: it doubles peak connection demand and starves the Hikari pool under send
bursts (proven by `MessagingConcurrencyTest` failing with pool exhaustion
before the switch). Listener exceptions therefore fail the publishing
operation loudly — acceptable because `dispatch` only throws on catastrophic
DB failure (per-channel errors are caught per delivery).

## 2. Event catalog (wired publishers)

| Event | Publisher | Recipients | Priority |
|---|---|---|---|
| `ApplicationAcceptedEvent` | `WorkflowService.transitionApplication` (FINAL_DECISION/APPROVED) | candidate | HIGH |
| `ApplicationRejectedEvent` | same (…/REJECTED) | candidate | HIGH |
| `InternshipAssignedEvent` | `InternshipService.assign` | intern + supervisor (role-specific texts) | HIGH |
| `DocumentVerifiedEvent` | `DocumentService.verifyApplicationDocument` | candidate | NORMAL |
| `TaskAssignedEvent` | `CompanionService.createTask` (non-self assignments) | assignee | NORMAL |
| `JournalEntryValidatedEvent` | `CompanionService.validateJournalEntry` | intern author | NORMAL |
| `NewPrivateMessageEvent` | `MessagingService.sendMessage` | other active members (sender excluded) | LOW |

`CertificateAvailable` / `PaymentApproved` have no producing actions yet —
their events belong to Phase A11 with the finance/certificate modules (the
generic `dispatch` path already supports them, no changes needed).

## 3. Fan-out & channels

One `Notification` per fact; one `NotificationDelivery` per recipient per
channel. Selection rule (pure, unit-tested): IN_APP always; EMAIL for
HIGH/URGENT when `steg.notifications.mail.enabled=true` and the recipient's
`users.email_notifications_enabled` opt-in; PUSH for URGENT only.

No paid SaaS: EMAIL via `JavaMailSender` over operator SMTP (free/dev provider
fine); IN_APP persisted + best-effort live push to the recipient's personal
`/user/queue/notifications` STOMP destination (Phase A9 broker; offline users
converge via REST). PUSH is a no-op stub (`PushNotificationSender`) — wire
FCM/APNs plus a device-token registry behind the same port later, zero caller
changes.

## 4. Failures & retry

Channel exceptions → delivery FAILED with `failureReason`, `attemptCount+1`,
`next_retry_at = now + min(3600, 60·2^(attempt-1))`. A `@Scheduled` sweep
(default 60 s, configurable) retries FAILED rows with attempts left, in
batches of 100, each row isolated. Exhausted rows stay FAILED (dead-letter,
queryable) — never silently dropped.

## 5. REST (all strictly scoped to the caller's own IN_APP deliveries)

`GET /api/notifications` (paginated, `unreadOnly` filter) ·
`GET /api/notifications/unread-count` · `POST /api/notifications/{id}/read` ·
`POST /api/notifications/read-all`. Reading another user's id yields 404.

## 6. Configuration

`steg.notifications.mail.{enabled,from}`, `spring.mail.*` (`SMTP_*` env),
`steg.notifications.retry.{fixed-delay-millis,max-attempts,
base-backoff-seconds,max-backoff-seconds}`. Migration V21 adds
`attempt_count`/`next_retry_at` + `users.email_notifications_enabled`
(default TRUE). Mail disabled (dev default) simply skips EMAIL creation.

## 7. Drive-by production fix (found by A10 tests)

`AuthzService` membership checks (`isSupervisorOf/isInternOf/isParticipantOf/
isOwnConversation`) traversed lazy associations with no ambient transaction,
500ing (`LazyInitializationException`) on any non-transactional request path —
masked until now because every existing test runs `@Transactional`. Checks now
run in short read-only transactions (behavior unchanged inside tests).

## 8. Verification

`NotificationIntegrationTest` (6: HIGH fan-out IN_APP+EMAIL, NORMAL IN_APP-only,
chat fan-out with sender excluded, record→recover→dead-letter retry, own-only
auth + unread filter/count, journal validation), `NotificationChannelSelectionTest`
(3, pure rule), plus the A9 concurrency suite as the pool-safety regression.
No notification content carries CIN, secrets, or document bytes (message alerts
reference sender/conversation only).
