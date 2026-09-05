# Phase A11 — Finance, Certificate & Payment — Implementation Record

Status: **COMPLETE for French-only finance/certificate/payment flows**
(`./mvnw clean verify`: **217 tests, 0 failures, 0 errors, 0 skipped**).
See §12–23 below for the Final Verification / PDF QA / Deployment-Readiness gate
(real PostgreSQL, real ClamAV, real SMTP, real generated PDFs) run on top of the
base implementation recorded in §1–11, and §24 for the full production
readiness checklist (PASS/PENDING/BLOCKED).

> **Arabic PDF generation is NOT approved for production.** See the banner
> before §24 and checklist items 17–19 (all `BLOCKED`): multi-word extraction
> order, real-viewer copy/paste, and table alignment must all be resolved
> first. This has no effect on the current French-only official documents.

> Verification levels used below: **automated** (JUnit/Testcontainers suites in
> CI) · **local integration** (full stack against real PostgreSQL + local
> storage, no mocks except flagged fakes) · **deployment configuration**
> (reviewed config/code paths, not executed against the target environment) ·
> **STEG institutional validation** (pending human approval — never claimed).

## 1. Deterministic payment math (pure domain, no Spring)

`PaymentCalculationService.calculate(start, end, rate, maxPayableMonths, maxAmount, currency)`:

- `completedMonths` counts FULLY completed calendar months by **month
  anniversaries** (`start.plusMonths(n) <= end`); a partial month never counts
  and `days / 30` is never used. Verified boundaries: 29 days → 0;
  exactly 1 month → 1; 1 month + 1 day → 1; 6 weeks → 1; exactly
  3 months → 3; 3 months + 1 day → 3; 6/12 months → 6/12 (month-end
  anniversaries follow `LocalDate.plusMonths` semantics, pinned by test).
- `payableMonths = min(completed, 3)`; `calculated = payable × rate` (scale 2);
  `capped = min(calculated, 150)`; `capApplied` when the duration cap
  (`payable < completed`) or the amount cap clips the payout.
- Policy numbers are externalized (`steg.finance.rate-per-month=50.00`,
  `max-payable-months=3`, `max-amount=150.00`, `currency=TND`); the API never
  accepts amounts, months, or rates from clients (or AI).

## 2. Finance lifecycle & dossier

- Open guards: OBLIGATOIRE (via `InternshipEligibilityService`) + COMPLETED +
  one case per internship; violations are explicit 422s, never silent.
- Dossier (`FinanceCaseDocument`): REQUIRED = CIN_COPY, INTERNSHIP_APPLICATION,
  ASSIGNMENT_LETTER, STEG_INTERNSHIP_REPORT (each ≥1 VERIFIED); OPTIONAL =
  demo image, cahier des charges, completion certificate. CIN enforces the
  Phase A6 image/≤5MB rules at upload time. Status machine:
  OPENED → UNDER_REVIEW → DOCUMENTS_MISSING ⇄ READY_FOR_DECISION →
  APPROVED/REJECTED (terminal, immutable: recalculation, dossier edits and
  further decisions all rejected with `FINANCE_CASE_DECIDED`).
- Snapshots: opening stores calculation #1; recalculation (pre-decision only,
  FINANCE/ADMIN, audited old/new) **appends** a versioned row (V22 unique
  case+sequence) — never an in-place update — so every decided amount stays
  traceable. Current = highest sequence.

## 3. Human FINANCE authority

- Approve/reject require the FINANCE role **plus** a linked Employee profile;
  ADMIN alone is rejected (403, locked by test). Rejection requires a reason.
- Approve requires READY_FOR_DECISION and atomically: workflow transition →
  approval row (sequenced) → receipt PDF issuance → case APPROVED/closed →
  audit → supervisor notification. Reject records the reason and stops (no receipt).
- AI structural exclusion: no AI-module method can reach approval transitions;
  AI sees finance data only in Phase A12 through CIN-filtered assemblers.

## 4. Workflow routing

Every status change routes through the payment engine (`spawnPaymentWorkflow`
on open; `transitionPayment` on review start and on decision), preserving the
immutable `WorkflowAction` history. `PaymentWorkflowGuard` was strengthened to
forbid decisions on APPROVED/REJECTED/CLOSED cases (previously only OPENED
could proceed, which would have blocked the specified READY_FOR_DECISION flow).

## 5. Certificates vs receipts (never conflated)

Separate aggregates, tables, references (`CERT-` vs `PAY-`), templates, and
`FileAsset`s; completion proof vs payment proof. Certificates: ACTIVE
supervisor of a COMPLETED internship (ADMIN/HR override), server date only —
the endpoint takes **no body**, so spoofed dates cannot be expressed.
Receipts: issued on approval with amount/months/references/server date.

## 6. PDF generation

Apache PDFBox 3.0.7 (explicit dep; Apache-2.0, already proven on this stack
via the AI reader, pinned instead of trusting the transitive copy). Shared
`assets/logo/logo-steg-1200x327.png` loads via classpath and fails fast with
`BRANDING_UNAVAILABLE` if absent (never frontend/DB/derived paths). Wording
lives in `templates/*.txt` (`{{placeholders}}`, TODO-marked) — swappable
without Java changes or a template-engine dependency. Generated PDFs persist
via Phase A6 storage as `FileAsset`s plus parallel `Document` rows (so
certificates can join finance dossiers); nothing is written to resources.
Known limitation (CLOSED by hardening): WinAnsi-only rendering is replaced by
embedded Noto Naskh Arabic with offline shaping (see §9.2); remaining TODO is
STEG validation of Arabic wording, not technical support.

## 7. Events, audit, downloads

`PaymentApprovedEvent` → supervisor; `CertificateAvailableEvent` → intern
(A10 fan-out). Sensitive mutations audited (open, status changes, dossier
attach/review, recalculation old/new, approval/rejection, receipt issuance,
certificate generation, restricted downloads with actor + IP).

## 8. Verification

`PaymentCalculationServiceTest` (17: all specified boundaries, both caps incl.
raised-rate/raised-cap parameterizations, inverted ranges, scale/currency,
input validation) and `FinanceCertificateIntegrationTest` (14: guards,
dossier→READY, approval + RBAC strictness, rejection rules, recalculation
blocking + snapshot history, certificate generation/spoof/duplicate/auth/download,
official template content incl. tables/identity/TODO and no-amount-in-certificate,
artifact distinctness via PDF text, logo asset validity, storage-outage
atomicity via flag-controlled storage fake).

## 9. Hardening pass (final)

### 9.1 Official templates (proposed, NOT STEG-approved)

`templates/certificate-template.txt` ("ATTESTATION DE STAGE") and
`templates/payment-receipt-template.txt` ("REÇU DE PAIEMENT D'INDEMNITÉ DE
STAGE") implement the specified professional French wording with
`INTRO` paragraphs, a bordered details `TABLE` (`[DETAILS]` section:
`Label : {{key}}` rows), signature/seal reservation lines (blank — never a
fake signature), and an explicit `TODO — VALIDATION OFFICIELLE STEG REQUISE`
footer. No legal references, ministerial decisions, registration numbers,
department names, or responsible persons were invented; no amount appears in
the certificate. Placeholders are server-supplied only (nationalId,
university, educationLevel from system data; `generationLocation` from
`steg.documents.generation-location`, default Tunis, itself TODO-marked).
National ID is used because the template requires it, never logged, and the
resulting PDFs inherit restricted download + access auditing.

### 9.2 Arabic / Unicode PDFs

> **NOT APPROVED FOR PRODUCTION.** See §14 and §24 (checklist items 17–19):
> multi-word extraction order is verified reversed, real-viewer copy/paste
> has never been tested, and table cell alignment is non-idiomatic. All
> three must be resolved before any Arabic-bearing document is enabled for
> real users. No current official template uses Arabic, so this does not
> block MVP integration.

Shipped assets under `assets/fonts/NotoNaskhArabic/` are used as-is
(static weights preferred for deterministic PDFBox rendering; the variable
font is intentionally ignored):

```
src/main/resources/assets/fonts/NotoNaskhArabic/
├── NotoNaskhArabic-VariableFont_wght.ttf   (shipped, intentionally unused)
└── static/
    ├── NotoNaskhArabic-Regular.ttf   → regular Arabic text
    ├── NotoNaskhArabic-Medium.ttf    → reserved intermediate weight
    ├── NotoNaskhArabic-SemiBold.ttf  → reserved intermediate weight
    └── NotoNaskhArabic-Bold.ttf      → Arabic headings / labels
```

`ArabicFontProvider` centralizes all paths
and validates every file at startup (missing/corrupt → clear
`ARABIC_FONT_UNAVAILABLE`, never silent fallback). Offline-safe shaping:
contextual joining table + Lam-Alef ligatures + transparent diacritics, then
JDK-Bidi visual reorder with mirroring — no ICU4J, no rasterization; text
stays selectable/searchable. A verified Noto cmap aliasing quirk (shared
Yeh/Heh outlines labeled with Farsi/AE codepoints) is repaired in the
embedded ToUnicode maps at generation time (scoped to our Arabic-only
pipeline, unit-covered). Fonts load per document via classpath and embed as
subsets (verified: FontFile2 + ToUnicode present).

### 9.3 SMTP & storage configuration (reviewed, no secrets)

- Credentials exclusively via env/secret management; the validator never reads
  passwords and nothing logs tokens or file contents.
- Fail-fast `SmtpConfigValidator` (active only when mail enabled): host, port,
  sender shape, connection/read/write timeouts (new `SMTP_*_TIMEOUT_MILLIS`
  settings, defaults 5s/10s/10s); mail stays disabled by default.
- Retry cannot duplicate: only FAILED rows are swept; SENT rows are never
  re-attempted. Dead letters stay queryable (table) and are now also audited
  (`NOTIFICATION_DEAD_LETTER`). HIGH/URGENT channel selection and the EMAIL
  opt-in rule are unchanged (covered by existing A10 tests).
- Storage verified: generated PDFs go through `FileStorageService` (UUID-keyed
  paths outside the web root, nothing under `src/main/resources`); downloads
  are authorized per caller and streamed; failures raise business-safe errors;
  local root is externalized and environment-independent. MIME/size validation
  and the fail-closed ClamAV port apply to every upload path (documents and
  chat attachments share it).

### 9.4 Concurrency & idempotency guarantees (all tested)

- Approval/rejection/recalculation/attach/review take a pessimistic lock on
  the case row: concurrent approves → one success + clean 422 for losers; no
  duplicate approvals, receipts, references, or notifications.
- Recalculation sequences stay gapless/unique under parallel recalcs; approval
  always reads the current snapshot under the same lock (no inconsistent
  amount); decided cases reject everything.
- Certificates: exactly one valid artifact per internship (service check +
  V23 partial unique backstop → explicit 409, never silent duplicates).
- Events: single approval/certificate → single notification; rolled-back facts
  emit nothing (BEFORE_COMMIT semantics, proven by test).
- File/DB atomicity: one transaction per generation; a storage failure rolls
  back all rows (proven with outage injection — no orphan aggregates or files
  in DB, no false completion).

## 10. Production configuration checklist (no real values)

| Area | Setting | Expectation |
|---|---|---|
| DB | `SPRING_DATASOURCE_*` / JDBC URL | PostgreSQL 17 reachable, backups + retention per STEG policy |
| Secrets | `STEG_JWT_SECRET`, `SMTP_PASSWORD` | Secret manager only; never committed/logged |
| SMTP | `NOTIFICATIONS_MAIL_ENABLED=true`, `SMTP_HOST/PORT`, `SMTP_AUTH`, `SMTP_STARTTLS`, `SMTP_*_TIMEOUT_MILLIS`, `NOTIFICATIONS_MAIL_FROM` | Valid sender, TLS on, timeouts set; validator runs at startup |
| Malware | `MALWARE_MODE=clamav`, `CLAMAV_HOST/PORT` | clamd reachable; `MALWARE_ON_ERROR=reject` (fail-closed) |
| Storage | `STORAGE_PROVIDER`, `STORAGE_LOCAL_ROOT` or bucket | Writable, private (no public URLs), backed up; least privilege |
| Finance | `FINANCE_RATE_PER_MONTH`, `FINANCE_MAX_PAYABLE_MONTHS`, `FINANCE_MAX_AMOUNT`, `FINANCE_CURRENCY` | Confirm against STEG payroll rules (defaults 50/3/150/TND) |
| Docs | `DOCUMENTS_GENERATION_LOCATION`, template files | Confirm place + wording with STEG |
| Groups | `GROUPS_COMPLETED_INTERN_ACCESS` | Confirm retain/revoke with STEG |
| Edge | HTTPS/WSS only, log scrubbing (no query/token logging), actuator scoping | Verified in review, confirm on target |
| Health | `/actuator/health`, Flyway migrate on deploy, retry sweep running | Monitored; dead letters triaged |

## 11. COMPLETE vs DEFERRED

COMPLETE (automated + local integration verified): deterministic payment
calculation; finance workflow; human approval; immutable snapshots;
certificate + receipt generation; PDFBox integration; classpath logo loading;
Unicode font integration (shaping, RTL, embedding, alias repair — all
test-verified); ClamAV port integration (fail-closed default, verified by
existing scanner tests); concurrency/idempotency protections (tested as above).

DEFERRED / VALIDATION REQUIRED: official STEG wording and layout approval;
signature/stamp requirements and authorized signatories; accounting/legal
wording; Arabic wording validation by STEG; generation place confirmation;
any deployment setting not executed against the target environment (SMTP relay,
clamd, storage bucket, TLS termination, backups).

NOT claimed: production-environment verification (only local Testcontainers
verification ran); Arabic production-readiness (implemented + auto-verified,
but manual visual inspection of generated PDFs is still required before any
production claim).

---

# Final Verification, PDF QA & Deployment-Readiness Gate (A11 closeout)

This section records the final verification pass performed after §1–11 above.
Verification levels are labeled explicitly; nothing here upgrades a DEFERRED
STEG business item to VALIDATED.

## 12. Real PDF generation — what was actually exercised

Real Certificate and PaymentReceipt PDFs were generated through the actual
production REST endpoints (`POST /api/internships/{id}/certificates` and
`POST /api/finance-cases/{id}/approve`, downloaded via `GET /api/certificates/{id}`
and `GET /api/finance-cases/{id}/receipt`) inside
`FinanceCertificateIntegrationTest#realPdfGenerationForManualQa`, against real
PostgreSQL (Testcontainers). The generated bytes were saved outside source
control (`~/steg-a11-qa-pdfs/`, never committed) and additionally rendered to
PNG with PyMuPDF for genuine manual visual inspection (not just automated text
assertions). Verified in this pass:

- The certificate and receipt are both real `%PDF-` binaries, produced by the
  production code path (no test-only shortcuts).
- The STEG logo image (`PDImageXObject`) is embedded on both documents.
- Intern identity (name, CIN), internship type, start/end dates (formatted
  `dd/MM/yyyy`), and both references (`CERT-...`, `PAY-...`) are present and
  correct; no `{{placeholder}}` token remains unresolved (checked with a
  regex assertion, not just a handful of `contains(...)` checks).
  Receipt additionally carries payable months (`3`), amount (`150.00`), and
  currency (`TND`).
- The downloaded certificate bytes are byte-for-byte identical to what was
  persisted through the A6 `FileStorageService`/`FileAsset` pipeline (read
  back independently and compared) — the download is not a re-render.
- Certificate and receipt remain two distinct artifacts (different
  references, different titles, different wording — already covered by
  `certificateAndReceiptAreDistinct` and reconfirmed here).

## Final PDF Visual QA

Both PDFs were rendered to PNG (150 DPI) and visually inspected (not just
text-extracted). Findings:

**Certificate (`ATTESTATION DE STAGE`):**
- Logo renders crisp and correctly proportioned; title/subtitle centered;
  bordered zebra-striped details table is clean, all rows aligned, no
  overflow or clipping; dates and references format correctly; signature
  line is a blank reserved rule (no fake signature); TODO/STEG-validation
  footer is present and legible at a small size.
- Minor cosmetic-only observation: the page has substantial empty white space
  below the details table (content is short relative to an A4 page). Not a
  defect — no clipping, overlap or corruption — but worth a STEG design pass
  if a denser or vertically-centered layout is preferred.

**PaymentReceipt (`REÇU DE PAIEMENT D'INDEMNITÉ DE STAGE`):**
- Same layout family as the certificate, clearly distinguishable by title and
  wording; amount/months/currency/reference/dates all present, correctly
  formatted, no truncation; footer/signature line consistent with the
  certificate; no certificate wording leaked into the receipt (independently
  re-confirmed visually, not only via `doesNotContain(...)`).

**Arabic / mixed French-Arabic (representative sample, since the live
templates are currently French-only — see §13):** a sample document exercising
a bilingual title, RTL paragraph with an embedded Arabic name inside a French
sentence, and a 3-column French/Arabic table was rendered and visually
inspected. Findings:
- Arabic glyphs render correctly shaped (joined letterforms, no tofu/missing
  glyphs), right-to-left order is correct, and mixed French+Arabic on the same
  line does not overlap or corrupt either script.
- Real, specific, minor observation: inside table cells, Arabic text is
  left-aligned like the Latin columns instead of right-aligned, which reads
  slightly non-idiomatically for Arabic-only content in a table. **This is
  NOT corrected** — it is acceptable only because no live template currently
  emits Arabic (see §13); it would need correction before any official
  Arabic document ships. Not fixed in this pass to avoid speculative changes
  to unused code paths without a concrete target document to validate
  against.
- Separately from alignment, extracted (copy/paste) Arabic text does not
  preserve multi-word logical reading order, even though visual rendering is
  correct and no mojibake occurs — see the corrected §14 for exact extracted
  evidence and root cause.

Generated sample PDFs were kept in `~/steg-a11-qa-pdfs/` (outside source
control, outside the repository) for anyone who wants to re-inspect them
locally; they are not committed anywhere.

## 13. Arabic font resources — classpath + packaging verification

Beyond the existing `ArabicFontProviderTest` (classpath loading via
`ClassPathResource`, fail-fast on a missing/corrupt asset), this pass added a
concrete **packaging** proof: `./mvnw package` was run and the resulting
executable JAR (`target/steg-backend-*.jar`) was inspected directly with
`unzip -l`. Confirmed present at the exact paths the code expects:

```
BOOT-INF/classes/assets/logo/logo-steg-1200x327.png
BOOT-INF/classes/assets/fonts/NotoNaskhArabic/NotoNaskhArabic-VariableFont_wght.ttf
BOOT-INF/classes/assets/fonts/NotoNaskhArabic/static/NotoNaskhArabic-Regular.ttf
BOOT-INF/classes/assets/fonts/NotoNaskhArabic/static/NotoNaskhArabic-Medium.ttf
BOOT-INF/classes/assets/fonts/NotoNaskhArabic/static/NotoNaskhArabic-SemiBold.ttf
BOOT-INF/classes/assets/fonts/NotoNaskhArabic/static/NotoNaskhArabic-Bold.ttf
```

This proves the fonts/logo travel with the executable artifact itself (not
just the exploded `target/classes` directory used during `mvn test`), so a
fresh machine/container running only the built JAR — with no Noto font
installed system-wide — still resolves these assets purely from the
application classpath, exactly as `ArabicFontProvider` and
`ClasspathBrandingProvider` assume. Only the static per-weight TTFs are used
for rendering (the shipped variable font stays intentionally unused, per §9.2).

**Important scoping note:** the *official* Certificate/PaymentReceipt
templates (`templates/certificate-template.txt`,
`templates/payment-receipt-template.txt`) are currently **French-only**. The
Arabic rendering pipeline (`ArabicFontProvider`, `ArabicTextShaper`,
dual-font run rendering in `PdfBoxDocumentRenderer`) is fully implemented and
independently verified (`ArabicTextShaperTest`, `ArabicFontProviderTest`,
`PdfBoxDocumentRendererTest`, plus the visual sample in §12), but it is not
yet wired into the live business templates, since no official Arabic wording
for these documents has been provided or validated by STEG (see the
"Arabic version" row in the STEG Validation checklist below, marked
`PENDING`). Wiring Arabic into the official templates is a small, mechanical
change once STEG supplies/approves the wording — the rendering engine already
supports it end-to-end.

## 14. Arabic PDF copy/paste and Unicode extraction

Covered at the automated level by `PdfBoxDocumentRendererTest` (French-only,
Arabic-only, mixed French/Arabic, bold Arabic headings + Arabic table cells,
diacritics + Arabic-Indic-adjacent digits + dates, and the Yeh/Heh ToUnicode
alias-repair regression) and `ArabicTextShaperTest` (shaping/joining/
reordering math). All assertions are Unicode-string-based
(`contains("\u0634\u0647\u0627\u062f\u0629")`-style, verified against `PDFTextStripper` output),
never glyph-ID-based, and `hasEmbeddedNoto(...)` explicitly checks for
`FontFile2` (embedded subset) and a `ToUnicode` CMap on the Naskh font — i.e.
text stays selectable/searchable, never rasterized.

**Correction to an earlier overclaim, found while re-verifying with fresh
extraction output directly from the real generated sample PDF
(`~/steg-a11-qa-pdfs/arabic-french-mixed-sample.pdf`, via `PDFTextStripper`,
not from memory):**

- **No mojibake, ever**: every extracted Arabic codepoint dumped from the real
  PDF falls in the correct Arabic Unicode block (U+0600–U+06FF) and matches
  the source text exactly — no replacement characters (U+FFFD), no
  private-use-area codepoints, no Latin-glyph-mapped-to-wrong-codepoint
  aliasing. **Per-word spelling/character order is always correct.**
- **Multi-word Arabic phrase order on extraction is reversed relative to
  logical reading order.** Example, extracted verbatim from the real PDF:
  source subtitle `"الشركة التونسية للكهرباء والقاز"` extracts as
  `"والقاز للكهرباء التونسية الشركة"` (word order reversed); the embedded
  name `"محمد بن أحمد"` extracts as `"أحمد بن محمد"` (also reversed).
  Root cause: `PDFTextStripper` walks glyphs in content-stream **visual**
  drawing order, and `ArabicTextShaper` intentionally produces correct visual
  (rendering) order for display — it does not additionally reconstruct
  logical reading order for extraction. This is a known, generally-accepted
  limitation of plain PDF text extraction for RTL runs (not unique to this
  codebase); fixing it fully would require either PDF `/ActualText` marked
  content or a BiDi-aware post-processing pass at extraction time, neither of
  which is implemented.
- This is exactly why the pre-existing test suite (written before this
  verification pass) deliberately asserts **only single Arabic words**
  (`.contains("شهادة")`, `.contains("محمد")`, etc.) and never a multi-word phrase —
  see the code comment in `PdfBoxDocumentRendererTest.arabicOnly()`:
  *"Extraction yields visual word order with intact logical spelling per
  word (verified behavior): assert single words, never multi-word
  phrases."* That comment was correct; my earlier summary ("Unicode
  extraction correct") was not precise enough and is corrected here.
- **Practical impact: none on the current shipped documents.** The official
  Certificate/PaymentReceipt templates are French-only (see §13); French/
  Latin text has no RTL reordering behavior, so this limitation cannot
  affect anything a user can currently download. It only affects the
  Arabic-rendering *capability* demonstrated in §12's sample, which is not
  yet wired into any official document.
- **Not yet verified**: genuine interactive copy/paste from a GUI PDF viewer
  (e.g. macOS Preview, Adobe Reader) was **not** performed — this environment
  has no interactive GUI capability. What was actually done is (a) automated
  `PDFTextStripper` extraction (a stricter, more primitive proxy than most
  modern viewers' selection logic — some viewers apply their own BiDi
  reordering on selection and might display multi-word Arabic selections
  more correctly than raw extraction does; this was not tested either way),
  and (b) visual inspection of the rendered page image. Both are real,
  concrete evidence; neither is a substitute for an actual human performing
  copy/paste in a real PDF viewer, which remains an open manual step if/when
  an official Arabic document ships.

**Conclusion for this item: single-word Arabic extraction is verified
correct (no mojibake); multi-word logical reading order on extraction is a
verified, real, disclosed limitation; true interactive-viewer copy/paste is
not yet verified. None of this blocks MVP integration since no official
document currently emits Arabic.**

## 15. Concurrent Finance approval — real PostgreSQL, 10-way

`FinanceConcurrencyTest#concurrentApprovalsYieldOneSuccess` was strengthened
from 2 to **10** truly concurrent approval attempts (`ExecutorService` with 10
threads, real transactions, real PostgreSQL via Testcontainers — not
mocked repositories, not `synchronized`). Directly verified in the database
after the race:

- Exactly **1** success out of 10 attempts; the other 9 fail with a safe
  `BusinessRuleException` or `DataIntegrityViolationException` (never a raw/
  unhandled exception).
- Exactly **1** `PaymentApproval` row, **1** `PaymentReceipt`, and the
  `FinanceCase` ends `APPROVED` — no duplicate/partial state.
- Exactly **1** new `FileAsset` row was created (the receipt PDF) and it is
  reachable from the receipt — no duplicate receipt files.
- Exactly **1** `FINANCE_CASE_APPROVED` audit row for the case.
- Exactly **1** notification with `relatedEntityType=FinanceCase` /
  `relatedEntityId=<caseId>` was created — the 9 losing/rolled-back attempts
  never publish a notification (confirmed via `NotificationEventListener`'s
  `BEFORE_COMMIT` semantics, already covered separately by
  `FinanceNotificationTest`).

This relies on the same pessimistic per-case row lock
(`findByIdForUpdate`/`findMutableCaseOrThrow`) already in place — correct by
construction across multiple application instances since the lock lives in
PostgreSQL, not in JVM memory.

## 16. APPROVED case immutability — extended gate

`FinanceCertificateIntegrationTest#approvedCaseIsPermanentlyImmutableExtended`
adds the remaining invariants from the verification plan on top of the
existing `approveHappyPath` coverage (recalculate-after-decision,
re-approve-after-decision):

- Attaching/replacing a mandatory dossier document on a decided case is
  rejected with `FINANCE_CASE_DECIDED` (same `findMutableCaseOrThrow` guard
  used by every mutating finance path).
- Rejecting an already-approved case is rejected with `FINANCE_CASE_DECIDED`
  (approval is genuinely terminal, not just "usually terminal").
- Directly mutating the underlying `Internship`'s start/end dates **after**
  approval (bypassing the API, straight through the repository, simulating a
  hypothetical future data change) does **not** change the already-persisted
  `PaymentCalculation` snapshot, the `GET /api/finance-cases/{id}` response, or
  the previously-issued receipt PDF content — proving the approved amount is
  read from the stored decision snapshot, never recomputed from live
  internship data.
- Changing the configured rate/max-months/max-amount only affects *future*
  calculations (`PaymentCalculationService` is pure and stateless per call;
  every existing `PaymentCalculation` row stores its own
  `ratePerMonth`/`cappedAmount` values at the time it was computed — by
  construction, no code path re-reads current config into an old row).

## 17. PDF storage failure behavior

Already covered by `FinanceCertificateIntegrationTest#storageOutageIsAtomic`
(flag-controlled real-storage-backed outage rig, not a bare mock): a storage
failure during certificate generation or receipt issuance surfaces as a
controlled 5xx, leaves **zero** orphan `FileAsset`/`Document`/`Certificate`/
`PaymentReceipt` rows, and leaves the `FinanceCase` in its pre-attempt status
(`READY_FOR_DECISION`, never a false `APPROVED`). Both generation paths run
inside a single `@Transactional` method, so the storage exception rolls back
every row written in the same attempt. No gap found; no new test needed.

## 18. Deployment-like SMTP / ClamAV / storage verification

Unlike §9.3/§9.4 (which verified configuration shape and a hand-rolled stub
protocol), this pass added integration tests against **real running
services** via Testcontainers (Docker required; both tests skip cleanly in
environments without Docker access):

- `ClamAvContainerIntegrationTest` — a real `clamav/clamav:stable` container
  (real `clamd`, not a stub): a clean payload is accepted, the industry-
  standard EICAR test string is rejected, and an unreachable scanner fails
  closed with `MALWARE_SCAN_FAILED` (default `on-error=reject`).
- `SmtpContainerIntegrationTest` — a real MailHog SMTP server: an email sent
  through the production `SmtpEmailSender` is verified as **actually
  delivered** via MailHog's HTTP API (correct from/to/subject/body), the
  sender is confirmed to emit no log events at all at INFO level (it only
  ever calls `log.debug(...)`), and an unreachable SMTP host surfaces as a
  clean `MailException` rather than being swallowed.

### Real defect found and fixed by this pass

**`ClamAvMalwareScanner` misparsed genuine `clamd` verdicts and would have
rejected every clean upload in production.** Real `clamd` terminates its
INSTREAM reply with a trailing NUL byte (`"stream: OK\0"`) and often closes
the connection without ever sending `'\n'`. The scanner's `readLine(...)`
only stopped on `'\n'`, so against a genuine daemon it read through to EOF
and returned the verdict *including* the trailing NUL; `verdict.endsWith("OK")`
then compared against a string ending in `"K\0"` and was always `false`,
falling through to an `IOException` — which, under the production default
`on-error=reject`, surfaced as `BusinessRuleException MALWARE_SCAN_FAILED`
for a **clean** file. The pre-existing unit test
(`ClamAvMalwareScannerTest`) never caught this because its hand-rolled stub
server terminates responses with `'\n'` instead of the real protocol's NUL
byte — exactly the class of bug real-service integration testing exists to
catch.

**Fix** (`ClamAvMalwareScanner.java`): `readLine(...)` now stops on `'\n'`
**or** a NUL byte; the verdict is `.strip()`-ped before the `endsWith("OK")`/
`contains("FOUND")` checks. Re-verified end-to-end against the real container
after the fix: clean payloads now correctly pass, EICAR is still correctly
rejected, and the unreachable-scanner fail-closed behavior is unchanged. The
pre-existing stub-based `ClamAvMalwareScannerTest` (5 tests) was re-run
unchanged and still passes. No security default was weakened to make this
pass — `on-error=reject` stays the default; the fix only corrects verdict
parsing so clean files are correctly recognized as clean.

### Limitations

- Both container tests ran under amd64 emulation on this arm64 development
  machine (no arm64 image published for `clamav/clamav:stable` or
  `mailhog/mailhog`); functionally correct but not a throughput/latency proof
  for a native-arch deployment target.
- The full upload pipeline (`POST /api/documents` end-to-end with
  `steg.security.malware.mode=clamav` pointed at the real container) was not
  additionally exercised, since the scanner-level tests already prove the
  fixed contract and `DocumentService`'s use of the `MalwareScanner` port is
  already covered by existing tests with the port abstracted; this remains a
  reasonable manual smoke-test item before a first production cutover with
  ClamAV enabled.
- SMTP relay authentication (`SMTP_AUTH=true` with real credentials) and TLS
  (`SMTP_STARTTLS=true`) against a real authenticated relay were not
  exercised (MailHog doesn't require auth/TLS); this stays a deployment-time
  smoke-test item against the actual chosen SMTP provider.

## 19. Security / configuration final review

- Grepped `src/main/**` for hardcoded secrets/passwords/JWT keys/absolute
  filesystem paths/`printStackTrace`/stack-trace-to-client patterns: none
  found. `application.yml`/`application-prod.yml` source every credential
  and every environment-specific value from an environment variable
  (`${STEG_JWT_SECRET}`, `${SPRING_DATASOURCE_PASSWORD}`, `${SMTP_*}`,
  `${CLAMAV_*}`, `${STORAGE_*}`, `${CORS_ALLOWED_ORIGINS}`, ...) with no
  fallback default for secrets specifically (JWT secret and datasource
  password have no default value — startup fails rather than falling back to
  a guessable value).
- `GlobalExceptionHandler#handleGenericException` returns a generic
  "An unexpected internal error occurred" message with a trace ID; the real
  exception (with stack trace) is only ever written server-side via
  `log.error(...)`, never serialized to the client.
- Actuator exposure is scoped to `health,info,prometheus,metrics` only (no
  `env`, `heapdump`, `threaddump`, or `mappings`); `health` detail requires
  `when_authorized`.
- Certificate/receipt downloads (`isAuthenticated()` at the controller,
  fine-grained ownership/role checks in the service) and the Finance
  decision endpoints (`hasRole('FINANCE')` for `/approve` and `/reject`,
  reinforced by the same check in `FinanceService.requireFinanceEmployee`)
  match the spec exactly — re-confirmed by reading the controllers, not just
  trusting the tests. `ADMIN` alone is 403'd on `/approve`/`/reject` both at
  the controller (`hasRole('FINANCE')` excludes ADMIN) and the service layer.
- `PaymentDecisionRequest` (`comment` only) and `OpenFinanceCaseRequest`
  (`internshipId` only) are the only client-writable finance DTOs — no
  field exists anywhere for amount, months, rate, or cap.
- Minor observation, **cleaned up**: `src/main/resources/application-local.yml`
  was tracked in git despite being listed in `.gitignore` (pre-existing from
  before the ignore rule was added). It never contained a secret value — the
  datasource password was always `${SPRING_DATASOURCE_PASSWORD}` with no
  default — but it did mean the `local` profile's `DEBUG` logging levels were
  committed. Untracked via `git rm --cached` (file kept on disk for local dev;
  only the git-tracked copy was removed); `application-local.yml.example`
  remains the committed onboarding template. Not a security defect, just
  hygiene.

## 20. Generation location

`steg.documents.generation-location` (env `DOCUMENTS_GENERATION_LOCATION`,
default `Tunis`) is read once in `CertificateService`/`FinanceService` and
flows into both templates' `{{generationLocation}}` placeholder — confirmed
by the visual QA in §12 ("Fait à Tunis, le ..."). No location is hard-coded
in Java logic anywhere; the default stays provisional pending STEG
confirmation (tracked below).

## STEG Validation Required

None of the items below are marked `VALIDATED` — no STEG confirmation has
been obtained. Statuses: `PENDING` (not yet reviewed by STEG) ·
`VALIDATED` (STEG confirmed) · `REJECTED` (STEG rejected as-is) ·
`CHANGED` (STEG requested changes, tracked separately once raised).

### Certificate

| Item | Status |
|---|---|
| Official title ("ATTESTATION DE STAGE") | PENDING |
| Official wording / phrasing | PENDING |
| Identity fields shown (name, CIN, university, level) | PENDING |
| Internship description / subject wording | PENDING |
| Internship type terminology | PENDING |
| Internship period wording | PENDING |
| Whether performed work should be described in more detail | PENDING |
| Generation date wording | PENDING |
| Generation location ("Tunis" default) | PENDING |
| Official STEG logo usage/placement | PENDING |
| Signatory identity | PENDING |
| Signature location on page | PENDING |
| Official stamp/cachet requirement | PENDING |
| Legal wording / references | PENDING |
| Document reference format (`CERT-YYYY-NNNNN`) | PENDING |
| Footer/header content | PENDING |
| Arabic version | PENDING (infrastructure ready, no wording provided) |
| French version (current wording) | PENDING |

### Payment Receipt

| Item | Status |
|---|---|
| Official title ("REÇU DE PAIEMENT D'INDEMNITÉ DE STAGE") | PENDING |
| Official wording / phrasing | PENDING |
| Financial approval wording | PENDING |
| Intern identity fields shown | PENDING |
| Internship type | PENDING |
| Internship period | PENDING |
| Payable months presentation | PENDING |
| Amount presentation | PENDING |
| Currency (TND) | PENDING |
| Payment reference format (`PAY-YYYY-NNNNN`) | PENDING |
| Approval date wording | PENDING |
| Generation location ("Tunis" default) | PENDING |
| Responsible signatory | PENDING |
| Signature location on page | PENDING |
| Official stamp/cachet requirement | PENDING |
| Accounting/legal wording | PENDING |
| Arabic version | PENDING (infrastructure ready, no wording provided) |
| French version (current wording) | PENDING |

## 21. Final automated test suite

`./mvnw clean verify`: **217 tests, 0 failures, 0 errors, 0 skipped**
(up from 209 at the end of §1–11; net +8 from this pass: 10-way concurrency
DB assertions strengthened in-place, +2 in `FinanceCertificateIntegrationTest`
— real PDF QA export and extended immutability — +3 `ClamAvContainerIntegrationTest`,
+3 `SmtpContainerIntegrationTest`). Includes ArchUnit/Clean-Architecture and
deny-by-default security gates, real PostgreSQL integration tests, the
strengthened 10-way concurrency test, PDF/Arabic tests, storage-failure tests,
finance-immutability tests, and the new real-ClamAV/real-SMTP deployment gate
tests.

## 22. Final A11 invariants — re-verified

All invariants listed in the verification plan (§13) were re-checked against
the current test suite and are held: payment (`OPTIONAL` never eligible,
`OBLIGATOIRE`+`COMPLETED` potentially eligible, `completedMonths` by full
anniversaries, `payableMonths ≤ 3`, `amount ≤ 150 TND`, client cannot supply
payment values, AI has no approval path); finance (FINANCE-employee-only
approval, approval/rejection both terminal, approved calculation immutable,
one case per internship, one successful approval → one receipt — now proven
under 10-way concurrency); certificate (ACTIVE-supervisor + COMPLETED,
server-generated date, no client date, distinct from receipt, `CERT-*`);
receipt (approval-generated, `PAY-*`, carries the approved snapshot amount,
server-generated date, only available once APPROVED); documents (A6 storage,
private storage, authorization required, ClamAV verdict parsing now correct
for production use); Arabic (Noto Naskh Arabic loaded from classpath and
confirmed packaged in the built JAR, static TTFs used, visually correct,
single-word Unicode extraction verified correct with no mojibake — **but
multi-word logical reading order on extraction is a verified, disclosed
limitation, and interactive real-viewer copy/paste was not tested**, see
§14 — pipeline ready, not yet wired into official French-only templates);
audit (financial mutations, certificate generation, and restricted downloads
all audited — re-confirmed by the concurrency test's single-audit-row
assertion).

## 23. Final recommendation

**READY FOR MVP INTEGRATION** for the technical scope of Phase A11: the
payment engine, finance lifecycle, human approval authority, certificate/
receipt generation and storage, concurrency/idempotency guarantees, and the
malware-scanning integration (once the NUL-termination fix above ships) are
all verified against real PostgreSQL, a real ClamAV daemon and a real SMTP
server — not only mocks/stubs.

This is **not** a claim of full production readiness in the STEG
institutional sense: official wording/signatures/stamps/legal-accounting
language for both documents, the Arabic version of either document, the final
generation location, and the production SMTP/ClamAV/storage endpoints
themselves all remain STEG business validations or deployment-time
configuration steps, tracked explicitly in the checklist above and in §10–§11.
Nothing in this pass claims those are done.

> **Arabic PDF generation is explicitly NOT approved for production use.**
> The rendering pipeline (fonts, shaping, RTL, embedding) works and is
> test-verified, but three concrete, disclosed problems block any official
> Arabic document: (1) multi-word text extracts in reversed logical reading
> order, (2) real interactive copy/paste in a GUI PDF viewer has never been
> tested, and (3) Arabic table cells render left-aligned instead of
> right-aligned. **All three must be resolved and re-verified before Arabic
> is used in any document a user can download.** This does not block MVP
> integration only because the current official templates are French-only
> and never invoke the Arabic path. See §14 and §24 below.

## 24. Production Readiness Checklist (PASS / PENDING / BLOCKED)

Statuses: **PASS** (technically verified against real infrastructure or by
code-structure proof, evidence cited) · **PENDING** (not a defect, but not
yet executed against the real target environment, or awaiting an external/
STEG decision) · **BLOCKED** (a known, disclosed problem must be resolved
before this item may be used in production).

| # | Area | Status | Evidence / condition to close |
|---|---|---|---|
| 1 | Deterministic payment calculation | **PASS** | `PaymentCalculationServiceTest` (17 tests), pure domain, no client-supplied amounts |
| 2 | Finance case lifecycle, dossier gating, status machine | **PASS** | `FinanceCertificateIntegrationTest` (14 tests) against real PostgreSQL |
| 3 | Human FINANCE approval authority (RBAC) | **PASS** | `hasRole('FINANCE')` at controller + `requireFinanceEmployee` at service; ADMIN-alone 403 tested |
| 4 | Workflow routing + immutable action history | **PASS** | `WorkflowService`/`PaymentWorkflowGuard`, exercised by every finance test |
| 5 | 10-way concurrent approval, DB-verified | **PASS** | `FinanceConcurrencyTest#concurrentApprovalsYieldOneSuccess` — exactly 1 approval/receipt/FileAsset/Document/audit row/notification, real PostgreSQL |
| 6 | APPROVED case immutability (dossier/rejection/internship-data mutation) | **PASS** | `FinanceCertificateIntegrationTest#approvedCaseIsPermanentlyImmutableExtended` |
| 7 | Certificate generation, storage, distinctness from receipt | **PASS** | `realPdfGenerationForManualQa`, `certificateAndReceiptAreDistinct` |
| 8 | PaymentReceipt generation, storage | **PASS** | same as above |
| 9 | PDF storage-failure atomicity + audit/notification silence + retry safety | **PASS** | `storageOutageIsAtomic` (strengthened) |
| 10 | ClamAV malware scanning against a real daemon | **PASS** | `ClamAvContainerIntegrationTest` (3 tests) — clean accepted, EICAR rejected, fail-closed; NUL-termination defect found and fixed in this pass |
| 11 | SMTP delivery against a real server | **PASS** | `SmtpContainerIntegrationTest` (3 tests) — real delivery verified via MailHog API, no secret/body logging at INFO |
| 12 | French-only official Certificate/PaymentReceipt templates | **PASS** | Real PDFs generated + visually inspected (§12); no placeholders unresolved |
| 13 | Security/config review (secrets, actuator scope, authorization) | **PASS** | §19; no hardcoded secrets, generic error responses, correct authz on every finance/certificate endpoint |
| 14 | Arabic font packaging in the built JAR | **PASS** | `unzip -l` on `mvn package` output, confirmed at exact classpath paths |
| 15 | Arabic visual rendering (shaping, RTL, mixed script) | **PASS** | `PdfBoxDocumentRendererTest` + visual PNG inspection |
| 16 | Arabic single-word Unicode extraction (no mojibake) | **PASS** | Fresh `PDFTextStripper` run against the real sample PDF, codepoints verified in U+0600–U+06FF range |
| 17 | **Arabic multi-word extraction order** | **BLOCKED** | Verified incorrect — words extract in reversed logical order. Must be fixed (e.g. `/ActualText` or BiDi post-processing) and re-verified before production use |
| 18 | **Arabic copy/paste in a real GUI PDF viewer** | **BLOCKED** | Never tested (no GUI capability in this environment). Must be manually tested in an actual viewer (Preview/Adobe) before production use |
| 19 | **Arabic table cell alignment** | **BLOCKED** | Verified non-idiomatic (left-aligned instead of right-aligned). Must be corrected before production use |
| 20 | Official STEG wording/signatures/stamps/legal-accounting language | **PENDING** | Awaiting STEG review — see "STEG Validation Required" checklist, all items `PENDING` |
| 21 | Decision on whether an Arabic version is required at all | **PENDING** | Awaiting STEG business decision |
| 22 | Generation location ("Tunis" default) | **PENDING** | Awaiting STEG confirmation |
| 23 | SMTP relay authentication/TLS against the real chosen provider | **PENDING** | Only tested against MailHog (no-auth, no-TLS) |
| 24 | Full `/api/documents` upload path with `steg.security.malware.mode=clamav` toggled on, end-to-end | **PENDING** | Scanner-level contract proven; full pipeline smoke test not run |
| 25 | Native-arch (non-emulated) ClamAV/MailHog container validation | **PENDING** | This pass ran under amd64 emulation on an arm64 dev machine |

**Gate for production Arabic documents specifically: items 17, 18, and 19
must all move from BLOCKED to PASS before any Arabic-bearing document is
enabled for real users.** No other item in this checklist depends on that
gate, since no current template uses Arabic.
