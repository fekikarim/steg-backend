# Phase A12 — AI Assistance Layer

> **Status:** ✅ Technically complete (closure pass 2026-09-05 — full suite green, live Gemini verified)  
> **Date:** 2026-09-05  
> **Scope:** Advisory-only AI integration — document analysis, finance-case summarisation, logbook generation, candidate Q&A, and human traceability review.
>
> **Readiness note:** this documents *technical* readiness only. STEG business validation
> (official wording, evaluation criteria, retention policy) and deployment-specific validation
> (production SMTP/ClamAV/storage endpoints, TLS, backups) remain PENDING and are tracked in §12.

---

## 1. Overview

Phase A12 introduces the **AI Assistance Layer** as a strictly advisory module.
The AI engine can produce analysis summaries and draft recommendations, but it can **never** directly mutate authoritative business state (applications, internships, finance cases, evaluations).
All AI output is persisted as `AiAnalysis` / `AiRecommendation` records in `PROPOSED` status; a human Employee must explicitly accept or dismiss each recommendation before any downstream action.

### Design constraints enforced architecturally

| Constraint | Mechanism |
|---|---|
| AI must not mutate business state | ArchUnit rule (`aiMustNotDependOnMutationServices`) banning direct import of 14 mutation service packages |
| CIN / national ID must never reach the AI | Repository-level query filtering (`restrictedAccess = false`) + ArchUnit rule (`aiAssemblersMustNotReferenceCinTypes`) |
| `cinExcluded = true` unconditionally | Enforced in `AiAnalysis` constructor and in every `AiService` flow before the API call |
| Graceful degradation | `AiCompletionClient` returns `AiCompletionResult.failure(...)` on any exception; service returns a controlled response without crashing the caller's workflow |
| Key never logged or hard-coded | `GeminiCompletionClient` reads key exclusively from `AiProperties`; no logging of the key, prompts, or response bodies (provider/model/status/length diagnostics only); API key passed as a query parameter at call time |
| Application layer stays clean | `AiService` depends ONLY on domain ports (`ai.domain.assembler.*`, `ai.domain.repository.*`, `ai.domain.client.*`) plus read-only `*.domain.repository` ports and `audit.application.AuditService`; infra assemblers/repositories implement the domain ports (enforced by `CleanArchitectureFitnessTest`) |
| No dead analysis types | `AiAnalysisType` contains exactly the 4 wired types (dead `INTERNSHIP_ASSISTANT_QUERY` / `TASK_ASSISTANCE` values removed in closure) |

---

## 2. Package Structure

```
ai/
├── domain/
│   ├── assembler/
│   │   ├── AiContentAssembler<C>          # Port — typed assembler contract
│   │   ├── ApplicationDocumentContentAssembler  # Port — application dossier
│   │   ├── FinanceCaseContentAssembler          # Port — finance dossier
│   │   ├── LogbookContentAssembler              # Port — logbook draft
│   │   ├── CandidateAssistantContentAssembler   # Port — candidate Q&A
│   │   └── AssembledAiContent             # Record — sanitized prompt payload
│   ├── client/
│   │   ├── AiCompletionClient             # Port — AI completion abstraction
│   │   └── AiCompletionResult             # Record — success/failure result
│   ├── model/
│   │   ├── AiAnalysis                     # Entity — persisted run record
│   │   ├── AiAnalysisType                 # Enum — 4 analysis types
│   │   ├── AiRecommendation               # Entity — advisory output awaiting human review
│   │   └── AiRecommendationStatus         # Enum — PROPOSED / ACCEPTED_BY_HUMAN / DISMISSED
│   └── repository/
│       ├── AiAnalysisRepository           # Domain port (implemented by infra JPA repo)
│       └── AiRecommendationRepository     # Domain port (implemented by infra JPA repo)
│
├── application/
│   ├── AiService                          # Use-case orchestrator (domain ports only, never infra)
│   ├── package-info.java
│   └── dto/
│       ├── AiAnalysisResponse
│       ├── AiAnalysisResultResponse
│       ├── AiRecommendationResponse
│       ├── AiRecommendationReviewRequest
│       └── CandidateAssistantQueryRequest
│
├── infrastructure/
│   ├── assembler/
│   │   ├── ApplicationDocumentAiContentAssembler  # implements ApplicationDocumentContentAssembler
│   │   ├── FinanceCaseAiContentAssembler          # implements FinanceCaseContentAssembler
│   │   ├── LogbookAiContentAssembler              # implements LogbookContentAssembler
│   │   └── CandidateAssistantAiContentAssembler   # implements CandidateAssistantContentAssembler
│   ├── client/
│   │   └── GeminiCompletionClient         # Adapter — Google Generative Language API v1beta
│   ├── config/
│   │   └── AiProperties                   # @ConfigurationProperties — steg.ai.*
│   ├── persistence/
│   │   ├── AiAnalysisRepository           # JPA repository implementing the domain port
│   │   └── AiRecommendationRepository     # JPA repository implementing the domain port
│   └── package-info.java
│
└── interfaces/
    └── rest/
        └── AiController                   # 5 secured endpoints
```

---

## 3. Domain Models

### `AiAnalysis` (`ai_analyses`)

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK (BaseEntity) |
| `type` | `VARCHAR(50)` | `AiAnalysisType` enum |
| `related_entity_type` | `VARCHAR(100)` | e.g. `"InternshipApplication"` |
| `related_entity_id` | `UUID` | FK-by-convention to the target entity |
| `model_used` | `VARCHAR(100)` | Model name at call time |
| `input_summary` | `TEXT` | Non-sensitive description of what was sent |
| `output_summary` | `TEXT` | AI-generated text or `AI_UNAVAILABLE:…` |
| `cin_excluded` | `BOOLEAN NOT NULL DEFAULT TRUE` | **Always true** — structural guarantee |
| `requested_by_id` | `UUID` | FK → `users` |
| `created_at` | `TIMESTAMP` | BaseEntity |

### `AiRecommendation` (`ai_recommendations`)

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK |
| `analysis_id` | `UUID NOT NULL` | FK → `ai_analyses` |
| `recommendation_text` | `TEXT NOT NULL` | Advisory output |
| `status` | `VARCHAR(50)` | `PROPOSED` → `ACCEPTED_BY_HUMAN` or `DISMISSED` |
| `reviewed_by_id` | `UUID` | FK → `employees` |
| `reviewed_at` | `TIMESTAMP` | Set on human review |
| `created_at` | `TIMESTAMP` | BaseEntity |

### `AiAnalysisType` enum

| Value | Trigger |
|---|---|
| `APPLICATION_DOCUMENT_ANALYSIS` | HR/ADMIN reviews a candidate's application dossier |
| `FINANCE_CASE_ANALYSIS` | Finance officer reviews an indemnity dossier |
| `LOGBOOK_GENERATION` | Intern or supervisor generates a draft carnet de stage |
| `CANDIDATE_ASSISTANT_QUERY` | Candidate asks a question via the virtual assistant |

---

## 4. API Endpoints

Base path: `/api/ai`

| Method | Path | Role(s) | Description |
|---|---|---|---|
| `POST` | `/applications/{id}/analyze` | `ADMIN`, `HR` | Analyze application documents (advisory). CIN excluded structurally. |
| `POST` | `/finance-cases/{id}/analyze` | `ADMIN`, `FINANCE` | Summarise finance case dossier. Restricted documents excluded. |
| `POST` | `/internships/{id}/logbook/generate` | `ADMIN`, `HR`, or internship participant | Generate draft logbook from journal entries, tasks, and deliverables. |
| `POST` | `/assistant/query` | `CANDIDATE` | Candidate virtual assistant — answers process questions scoped to the candidate's own context. |
| `POST` | `/recommendations/{id}/review` | `ADMIN`, `HR`, `FINANCE`, `SUPERVISOR` | Human marks a recommendation `ACCEPTED_BY_HUMAN` or `DISMISSED`. |

> All endpoints are secured with method-level `@PreAuthorize`. The ArchUnit `MethodSecurityFitnessTest` enforces this deny-by-default policy automatically.
> Runtime coverage lives in `ai/AiControllerSecurityTest.java` (18 MockMvc tests): `401` unauthenticated on all 5 endpoints; `403` for wrong roles (incl. FINANCE-only finance analysis, non-participant logbook, candidate-only assistant); happy-path `200`s; per-candidate assistant isolation (`relatedEntityId` scoping); Employee-authority review (`ADMIN` without `Employee` row → `404`); `400` validation envelopes and `422` for `PROPOSED` review.

---

## 5. CIN / Restricted Document Exclusion

CIN protection operates at two independent layers:

### Layer 1 — Repository query (structural exclusion)

Both document repositories expose dedicated query methods that hard-filter `restrictedAccess = false`:

```java
// ApplicationDocumentRepository
List<ApplicationDocument> findByApplicationIdAndDocumentRestrictedAccessFalse(UUID applicationId);

// FinanceCaseDocumentRepository
List<FinanceCaseDocument> findByFinanceCaseIdAndDocumentRestrictedAccessFalse(UUID financeCaseId);
```

The AI assemblers call **only** these filtered methods. A `CIN_COPY`-type document always has `restrictedAccess = true` and is therefore **invisible** to any assembler.

### Layer 2 — ArchUnit rule (compile-time enforcement)

`AiIsolationFitnessTest.aiAssemblersMustNotReferenceCinTypes` verifies that no class in `ai.infrastructure.assembler` directly imports or references any type whose simple name contains `"Cin"`. This prevents future accidental additions from bypassing layer 1.

### Layer 3 — Entity invariant

`AiAnalysis.cinExcluded` defaults to `true` in both the field declaration and the constructor, and is explicitly set to `true` before `aiAnalysisRepository.save(...)` in every `AiService` operation.

---

## 6. AI Client — Gemini Adapter

`GeminiCompletionClient` implements the `AiCompletionClient` port using Spring's `RestClient` against the **Google Generative Language API v1beta** (`generateContent` endpoint).

### Request payload shape

```json
{
  "systemInstruction": { "parts": [{ "text": "<system_instruction>" }] },
  "contents": [{ "role": "user", "parts": [{ "text": "..." }, ...] }],
  "generationConfig": { "temperature": 0.2, "maxOutputTokens": 2048 }
}
```

### Failure modes handled

| Scenario | Behaviour |
|---|---|
| `steg.ai.enabled=false` | Returns `failure("AI is currently disabled…")` immediately — no network call |
| API key blank/absent (incl. unresolved `${...}` placeholder literals) | Returns `failure("AI service is not configured with an API key.")` — no network call |
| Network timeout / HTTP error | Caught by `catch (Exception ex)` → returns `failure(…)` with provider/model/error-class/HTTP-status only (key, prompts, bodies, and exception messages never logged) |
| Empty response body | Returns `failure("Received empty response from Gemini API.")` |
| Response missing `candidates[0].content.parts[0].text` | Returns `failure("Model response did not contain text content.")` — only provider/model/response-length logged, never the body |

### Live verification (closure pass, 2026-09-05)

Opt-in test `ai/GeminiLiveVerificationIT.java` (name ends in `IT` so Surefire never runs it in normal CI; additionally gated on `AI_LIVE_VERIFY=true`; no Spring context, no DB, no persistence) performed **one real `generateContent` call** with the environment-provided key and the configured model:

- `provider=gemini`, `model=gemini-3.8-flash`, `success=true`, `~1.6s` elapsed, non-blank content returned.
- Only metadata was emitted (provider/model/success/content-length/elapsed); prompt, response text, and key were never printed or logged.

Result: the configured model identifier is valid and the v1beta request/response contract in `GeminiCompletionClient` is correct. Re-run explicitly via `AI_LIVE_VERIFY=true ./mvnw -Dtest='GeminiLiveVerificationIT' -DfailIfNoTests=false test` with `GEMINI_API_KEY` (or `AI_API_KEY`) exported.

---

## 7. Graceful Degradation

When `AiCompletionClient.complete(...)` returns `success = false`, `AiService`:

1. Persists the `AiAnalysis` record with `outputSummary = "AI_UNAVAILABLE: " + errorMessage`
2. Returns an `AiAnalysisResultResponse` with:
   - `recommendations = []` (empty list — no `AiRecommendation` row created)
   - `responseText` set to a French-language degraded message (`"Service IA temporairement indisponible: …"` or `"Service d'assistance temporairement indisponible: …"`)
3. Does **not** throw an exception — the caller's HTTP response is a normal `200 OK` with the degraded envelope

This means an AI outage **never blocks** the HR, Finance, or Internship workflows.

---

## 8. Configuration Reference

Defined under the `steg.ai` namespace in `application.yml`, all values sourced from environment variables:

```yaml
steg:
  ai:
    enabled:          ${AI_ENABLED:false}             # Feature flag — off by default
    provider:         ${AI_PROVIDER:gemini}
    api-key:          ${AI_API_KEY:${GEMINI_API_KEY:}} # Never logged; Spring-side fallback only
    model:            ${AI_MODEL:gemini-3.8-flash}     # Live-verified 2026-09-05 (see §6)
    base-url:         ${AI_BASE_URL:https://generativelanguage.googleapis.com}
    timeout-ms:       ${AI_TIMEOUT_MS:10000}          # Connect + read timeout
    max-output-tokens:${AI_MAX_OUTPUT_TOKENS:2048}
    temperature:      ${AI_TEMPERATURE:0.2}            # Low temp → deterministic, factual output
```

### Environment variables quick-reference

| Variable | Purpose | Default |
|---|---|---|
| `AI_ENABLED` | Master feature flag | `false` |
| `AI_API_KEY` / `GEMINI_API_KEY` | Gemini API key (set exactly ONE, plain literal) | _(empty — key is mandatory when enabled)_ |
| `AI_MODEL` | Gemini model identifier | `gemini-3.8-flash` |
| `AI_TIMEOUT_MS` | HTTP connect + read timeout | `10000` ms |
| `AI_MAX_OUTPUT_TOKENS` | Max tokens per completion | `2048` |
| `AI_TEMPERATURE` | Sampling temperature | `0.2` |

> **Key precedence:** `AI_API_KEY` wins when non-blank, otherwise `GEMINI_API_KEY` is used (Spring placeholder fallback in `application.yml`). Provide the key as a **plain literal in exactly one** of the two variables — do NOT write nested references such as `AI_API_KEY=${GEMINI_API_KEY}` inside `.env` files: plain `.env`/shell sourcing does not expand them, and the literal text would otherwise be sent as the key. Defence in depth: `GeminiCompletionClient.isApiKeyConfigured(...)` treats blank values **and** unresolved `${...}` literals as "key absent" (graceful degradation, no network call), covered by `GeminiCompletionClientTest.placeholderKeyPerformsNoNetworkCall` / `apiKeyConfiguredGuard`.
>
> **To enable AI in a deployment:** set `AI_ENABLED=true` and provide a valid key via environment/secret mechanism (`AI_API_KEY` or `GEMINI_API_KEY`). The service degrades gracefully if the key is absent, so leaving the flag `false` is safe for environments without API access. `.env` is gitignored; `.env.example` carries empty placeholders + this guidance only — never a real key.

---

## 9. Architectural Fitness Tests

Three test classes enforce the advisory-layer invariants automatically on every build
(all in `src/test/java/tn/steg/backend/`):

### `architecture/AiIsolationFitnessTest` (ArchUnit)

| Rule | What it checks |
|---|---|
| `aiMustNotDependOnMutationServices` | `ai.*` may not import any of 14 mutation service packages (application, internship, finance, evaluation, companion, workflow, document, comment, candidate, certificate, organization, messaging, notification, iam.application). Only `audit.application` is permitted; read-only `*.domain.repository` ports are allowed. |
| `aiAssemblersMustNotReferenceCinTypes` | Classes in `ai.infrastructure.assembler` may not reference any type whose simple name contains `"Cin"`. |
| `aiMustNotDependOnDocumentApplicationLayer` | Belt-and-suspenders: `ai.*` may not access `document.application.*` at all. |

`architecture/CleanArchitectureFitnessTest` additionally proves `AiService` depends only on domain ports (application → domain), never on `ai.infrastructure.*`.

### `ai/AiServiceGracefulDegradationTest` (JUnit 5 + Mockito)

5 pure unit tests (no Spring context loaded) covering all four service operations:

| Test | Scenario | Assertions |
|---|---|---|
| `analyzeApplication > returnsDegradedResponse_whenAiClientFails` | Client returns failure | `recommendations` empty, `responseText` starts with FR degradation message |
| `analyzeApplication > cinExcludedIsAlwaysTrue_evenOnFailure` | Client returns failure | All saved `AiAnalysis` records have `cinExcluded = true` |
| `analyzeFinanceCase > returnsDegradedResponse_whenAiClientFails` | Client returns failure | Same degradation contract |
| `generateLogbookDraft > returnsDegradedResponse_whenAiClientFails` | Client returns failure | Same degradation contract |
| `queryCandidateAssistant > returnsDegradedResponse_whenAiClientFails` | Client returns failure | French assistant message, no recommendations |

### `ai/AiCinExclusionIntegrationTest` (Testcontainers + PostgreSQL)

2 repository-level tests with a restricted (`CIN_COPY`) + normal document fixture per dossier:

| Test | Assertions |
|---|---|
| `applicationAssemblerExcludesRestrictedDocuments` | Unfiltered query sees 2 rows; filtered query sees exactly the normal ref; assembler `inputSummary` reports count 1 and prompt parts contain the normal ref but not the CIN ref |
| `financeCaseAssemblerExcludesRestrictedDocuments` | Same proof for the finance-case dossier path |

### `ai/AiControllerSecurityTest` (MockMvc + Testcontainers)

18 controller security tests over all 5 endpoints — see §4 note above for the matrix
(`401` / `403` / happy-path `200` / candidate isolation / logbook participation /
FINANCE-only / Employee-authority review / `400` + `422` envelopes).

### `ai/infrastructure/client/GeminiCompletionClientTest` (JDK stub HTTP server)

7 tests proving the v1beta contract (path `/v1beta/models/{model}:generateContent`, `key` query param, `candidates[0].content.parts[0].text` parsing) and logging safety: planted prompt/response/key markers never appear in any log event on success, malformed-response, or HTTP-500 paths; disabled/blank/placeholder keys perform zero network calls.

### Opt-in live test (excluded from normal CI)

`ai/GeminiLiveVerificationIT.java` (`*IT` suffix → never run by Surefire; additionally gated on `AI_LIVE_VERIFY=true`; no Spring/DB/persistence). Result 2026-09-05: `success=true` against `gemini-3.8-flash` in ~1.6s — see §6.

**Full suite result (closure pass):** `Tests run: 252, Failures: 0, Errors: 0, Skipped: 0` (live `IT` excluded by design; run it explicitly as documented in §6).

---

## 10. Data Flow Diagrams

### Happy-path — Application Document Analysis

```
HTTP POST /api/ai/applications/{id}/analyze
    │  (HR / ADMIN only)
    ▼
AiController.analyzeApplication(id, actor)
    │
    ▼
AiService.analyzeApplication(id, actor)
    ├── ApplicationDocumentAiContentAssembler.assemble(id)
    │       └── repo.findByApplicationIdAndDocumentRestrictedAccessFalse(id)   ← CIN excluded here
    ├── new AiAnalysis(type, …, cinExcluded=true)  →  aiAnalysisRepository.save()
    ├── GeminiCompletionClient.complete(systemInstruction, promptParts)
    │       └── POST https://generativelanguage.googleapis.com/v1beta/…
    ├── analysis.outputSummary = result.content()  →  save()
    ├── new AiRecommendation(analysis, content)   →  aiRecommendationRepository.save()
    ├── auditService.log("AI_APPLICATION_ANALYZED", …)
    └── return AiAnalysisResultResponse(analysis, [recommendation], content)
```

### Degraded-path — AI client failure

```
GeminiCompletionClient.complete(…)
    └── throws Exception (timeout / HTTP error / key missing)
            └── returns AiCompletionResult.failure(message, model, provider)

AiService
    ├── analysis.outputSummary = "AI_UNAVAILABLE: " + message  →  save()
    └── return AiAnalysisResultResponse(analysis, [], "Service IA temporairement indisponible: …")
        (no AiRecommendation created, no exception propagated)
```

### Human review flow

```
HTTP POST /api/ai/recommendations/{id}/review
    │  (ADMIN / HR / FINANCE / SUPERVISOR)
    ▼
AiController.reviewRecommendation(id, {status: ACCEPTED_BY_HUMAN}, actor)
    │
    ▼
AiService.reviewRecommendation(id, request, actor)
    ├── validate: status must not be PROPOSED
    ├── look up Employee reviewer
    ├── recommendation.status = ACCEPTED_BY_HUMAN
    ├── recommendation.reviewedBy = reviewer
    ├── recommendation.reviewedAt = now()  →  save()
    ├── auditService.log("AI_RECOMMENDATION_REVIEWED", …, "ACCEPTED_BY_HUMAN")
    └── return AiRecommendationResponse
```

---

## 11. Files Added / Modified

### New source files

| File | Role |
|---|---|
| `ai/domain/client/AiCompletionClient.java` | Port interface |
| `ai/domain/client/AiCompletionResult.java` | Result record |
| `ai/domain/assembler/AiContentAssembler.java` | Assembler port (generic contract) |
| `ai/domain/assembler/ApplicationDocumentContentAssembler.java` | Port — application dossier assembler |
| `ai/domain/assembler/FinanceCaseContentAssembler.java` | Port — finance dossier assembler |
| `ai/domain/assembler/LogbookContentAssembler.java` | Port — logbook assembler |
| `ai/domain/assembler/CandidateAssistantContentAssembler.java` | Port — candidate Q&A assembler |
| `ai/domain/assembler/AssembledAiContent.java` | Payload record (enforces `cinExcluded=true` in compact constructor) |
| `ai/domain/model/AiAnalysis.java` | JPA entity |
| `ai/domain/model/AiAnalysisType.java` | Enum — exactly 4 analysis types |
| `ai/domain/model/AiRecommendation.java` | JPA entity |
| `ai/domain/model/AiRecommendationStatus.java` | Enum |
| `ai/domain/repository/AiAnalysisRepository.java` | Domain port |
| `ai/domain/repository/AiRecommendationRepository.java` | Domain port |
| `ai/infrastructure/config/AiProperties.java` | `@ConfigurationProperties("steg.ai")` |
| `ai/infrastructure/client/GeminiCompletionClient.java` | Gemini adapter (safe diagnostics only; `${...}` placeholder guard) |
| `ai/infrastructure/assembler/ApplicationDocumentAiContentAssembler.java` | Assembler — application documents (implements domain port) |
| `ai/infrastructure/assembler/FinanceCaseAiContentAssembler.java` | Assembler — finance case (implements domain port) |
| `ai/infrastructure/assembler/LogbookAiContentAssembler.java` | Assembler — logbook generation (implements domain port) |
| `ai/infrastructure/assembler/CandidateAssistantAiContentAssembler.java` | Assembler — candidate Q&A (implements domain port) |
| `ai/infrastructure/persistence/AiAnalysisRepository.java` | JPA repository implementing the domain port |
| `ai/infrastructure/persistence/AiRecommendationRepository.java` | JPA repository implementing the domain port |
| `ai/application/AiService.java` | Use-case orchestrator (domain ports only) |
| `ai/application/dto/*.java` | 5 DTOs |
| `ai/interfaces/rest/AiController.java` | REST controller (5 secured endpoints) |

### New test files

| File | Type |
|---|---|
| `architecture/AiIsolationFitnessTest.java` | ArchUnit — 3 isolation rules |
| `ai/AiServiceGracefulDegradationTest.java` | JUnit 5 / Mockito — 5 degradation tests |
| `ai/AiCinExclusionIntegrationTest.java` | Testcontainers — 2 CIN-exclusion proofs (DB + assembler) |
| `ai/AiControllerSecurityTest.java` | MockMvc — 18 endpoint security tests (all 5 endpoints) |
| `ai/infrastructure/client/GeminiCompletionClientTest.java` | JDK stub server — 7 contract + logging-safety tests |
| `ai/GeminiLiveVerificationIT.java` | Opt-in live verification (excluded from normal CI by `*IT` suffix + `AI_LIVE_VERIFY=true` gate) |

### Modified source files

| File | Change |
|---|---|
| `application.yml` | Added `steg.ai.*` property block |
| `document/domain/repository/ApplicationDocumentRepository.java` | Added `findByApplicationIdAndDocumentRestrictedAccessFalse` |
| `document/infrastructure/persistence/ApplicationDocumentRepository.java` | JPA implementation of the above |
| `document/domain/repository/FinanceCaseDocumentRepository.java` | Added `findByFinanceCaseIdAndDocumentRestrictedAccessFalse` |
| `document/infrastructure/persistence/FinanceCaseDocumentRepository.java` | JPA implementation of the above |
| `ai/infrastructure/assembler/LogbookAiContentAssembler.java` | Bug-fix: `getTitle()` → `getReference()/getSubject()`, removed non-existent `getDepartment()`, `getContent()` → `getDescription()` |
| `ai/infrastructure/assembler/FinanceCaseAiContentAssembler.java` | Bug-fix: `getTitle()` → `getReference()/getSubject()` |

---

## 12. Known Constraints & TODOs

| Item | Detail |
|---|---|
| **Model name** | `gemini-3.8-flash` — live-verified working on 2026-09-05 via `GeminiLiveVerificationIT` (`success=true`, ~1.6s). Keep `AI_MODEL` externalized so a future model change needs no code edit. |
| **No streaming** | The current implementation uses synchronous `RestClient` with a single-shot response. Streaming (SSE) is a future enhancement. |
| **No prompt versioning** | System instructions are hardcoded strings in the assemblers. If STEG needs auditable prompt versions, externalise prompts to a configuration table. |
| **Candidate assistant scope** | The assistant is scoped to the candidate's own profile data (application status, deadlines), proven per-candidate by `AiControllerSecurityTest.assistantIsolatedPerCandidate`. It does not have access to other candidates or to HR internal notes. |
| **Token budget** | `maxOutputTokens` defaults to `2048`. For logbook generation over a long internship with many journal entries, consider raising `AI_MAX_OUTPUT_TOKENS` or chunking the prompt. |
| **STEG / deployment validation (PENDING, out of scope for code)** | Official document wording, evaluation criteria, retention policy, production SMTP/ClamAV/storage endpoints, TLS termination, and backups still require STEG or deployment-owner sign-off. Nothing in the green test suite claims those. |
