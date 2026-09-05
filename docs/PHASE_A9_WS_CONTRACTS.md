# Phase A9 — WebSocket Wire Contracts & Deployment Security

Client-facing STOMP contract for real-time messaging. Locked by
`MessagingContractTest` (REST/broadcast JSON shapes) and
`MessagingWebSocketIntegrationTest` (live frame flows). Any wire change must
update this doc and the tests together.

## 1. Transport

- Endpoint: `/ws` (native WebSocket) and `/ws` with SockJS fallback.
- Sub-protocol: STOMP over the socket; application prefix `/app`, broadcasts
  under `/topic`, personal errors under `/user/queue/errors`.
- The in-memory simple broker is single-instance. Multi-instance rollout needs
  an external broker (e.g. RabbitMQ/Redis) plus sticky upgrades — outside MVP.

## 2. Authentication frames

- STOMP `CONNECT` MUST carry native header `Authorization: Bearer <access-token>`.
- The HTTP upgrade SHOULD carry the same header; `?token=<jwt>` query fallback
  exists only for header-less environments. Prefer headers everywhere.
- Missing/invalid/expired credentials → handshake rejected (401) or CONNECT
  rejected (ERROR frame, no session). Tokens are short-lived (15 min); refresh
  happens over HTTPS REST, never over the socket.

## 3. Client → server destinations

| Destination | Payload (exact JSON) | Effect |
|---|---|---|
| `/app/conversations/{id}/send` | `{"content": "…"}` (1–4000 chars) | Persists as `SENT` with next `sequenceNumber`; broadcast follows |
| `/app/conversations/{id}/delivered` | `{"upToSequenceNumber": n}` (positive, ≤ max) | `SENT → DELIVERED` for the acker's received messages; transitions broadcast |
| `/app/conversations/{id}/read` | `{"upToSequenceNumber": n}` (positive, ≤ max, monotonic) | Advances read+delivery watermarks; eligible messages → `READ`; transitions broadcast |

Acks are idempotent on equality; rewinding a watermark → `SEQUENCE_REWIND`
error. Out-of-range (`> max`) → `SEQUENCE_OUT_OF_RANGE`.

## 4. Server → client frames

Subscribe: `/topic/conversations/{id}` (membership-gated — non-members get
nothing, the subscription is denied server-side) and `/user/queue/errors`.

Broadcast payload (`MessageResponse`, every field always present):

```json
{
  "id": "33333333-3333-3333-3333-333333333333",
  "conversationId": "22222222-2222-2222-2222-222222222222",
  "senderId": "11111111-1111-1111-1111-111111111111",
  "content": "hello",
  "status": "SENT",
  "sequenceNumber": 7,
  "sentAt": "2026-01-02T00:00:00Z",
  "editedAt": null,
  "deletedAt": null,
  "attachments": [
    {"id": "…", "fileAssetId": "…", "fileName": "report.pdf",
     "mimeType": "application/pdf", "size": 1234}
  ]
}
```

- `status`: `SENT | DELIVERED | READ | EDITED | DELETED` (lifecycle in
  `PHASE_A9_MESSAGING.md`). Deleted messages keep shape with
  `content: "[message deleted]"` and `attachments: []`.
- Error payload on `/user/queue/errors`: `{"code": "…", "message": "…"}`.
  Codes: `UNAUTHENTICATED`, `ACCESS_DENIED` (also used for unknown ids — no
  existence leak), `NOT_FOUND`, `EMPTY_MESSAGE`, `MESSAGE_TOO_LONG`,
  `INVALID_SEQUENCE`, `SEQUENCE_OUT_OF_RANGE`, `SEQUENCE_REWIND`,
  `SEND_FAILED`, `ACK_FAILED`, plus attachment codes (`INVALID_FILE_TYPE`,
  `FILE_TOO_LARGE`, `MALWARE_DETECTED`, `MALWARE_SCAN_FAILED`).

## 5. Client rules

1. Subscribe to the topic BEFORE sending; on reconnect, resubscribe and
   resync missed messages via REST history (`GET …/messages?cursor=`).
2. Treat broadcasts as the live signal; REST history is the authority on
   conflict (broadcasts are best-effort).
3. Ack delivery on receipt and read on view; never infer `READ` from delivery.
4. Never trust locally computed `sequenceNumber`s — the server assigns them.

## 6. Deployment security checklist

- [ ] Terminate TLS (`wss`) at the edge; never expose `ws://` publicly.
- [ ] Strip query strings from access logs (Tomcat: log `%U`, not `%U?%q`;
      nginx: `$uri`, not `$request_uri`) — `?token=` must never persist.
- [ ] CORS allow-list (`steg.cors.allowed-origins`) covers the WS handshake;
      review it per environment.
- [ ] Rate-limit `/ws` handshakes and `/app/**/send` at the gateway (no
      in-app WS rate limiter in MVP).
- [ ] Set `MALWARE_MODE=clamav` with a healthy clamd before untrusted uploads;
      keep `MALWARE_ON_ERROR=reject` (fail-closed).
- [ ] Confirm `GROUPS_COMPLETED_INTERN_ACCESS` with STEG policy
      (`retain` default; `revoke` for strict post-internship isolation).
- [ ] Proxy idle/WebSocket timeouts above the client heartbeat; document the
      reconnect procedure for mobile clients.
- [ ] Re-run the deployment tests (`MessagingWebSocketIntegrationTest`:
      tokenless/expired handshake rejection, non-member SUBSCRIBE/SEND
      containment) against the staging edge, not just localhost.
