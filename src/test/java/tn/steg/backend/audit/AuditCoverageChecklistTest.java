package tn.steg.backend.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A13 — deterministic audit coverage checklist (requirement §89).
 *
 * <p>For every operation the requirements mandate as auditable, this test
 * asserts that (a) the exact action code is still emitted by its responsible
 * service, and (b) that service wires the centralized audit writer. It is
 * deliberately source-based and fails loudly when a mandatory action is
 * renamed or dropped, forcing an explicit decision in review.
 */
@DisplayName("A13 — Audit coverage checklist (§89)")
class AuditCoverageChecklistTest {

    private static final Map<String, String> ACTION_TO_SERVICE = new LinkedHashMap<>() {{
        // Application accept/reject (WorkflowService — decision mapping helper)
        put("APPLICATION_ACCEPTED", "workflow/application/WorkflowService.java");
        put("APPLICATION_REJECTED", "workflow/application/WorkflowService.java");
        put("APPLICATION_NEEDS_CORRECTION", "workflow/application/WorkflowService.java");
        put("APPLICATION_UNDER_REVIEW", "workflow/application/WorkflowService.java");
        // Internship activation/completion (WorkflowService — transition ternary)
        put("INTERNSHIP_COMPLETED", "workflow/application/WorkflowService.java");
        put("INTERNSHIP_ACTIVATED", "workflow/application/WorkflowService.java");
        // Assignment / supervisor change (InternshipService)
        put("INTERNSHIP_ASSIGNMENT_ASSIGNED", "internship/application/InternshipService.java");
        put("INTERNSHIP_CANCELLED", "internship/application/InternshipService.java");
        put("INTERNSHIP_REQUIREMENT_CHANGED", "internship/application/InternshipService.java");
        // Journal + deliverable validation (CompanionService)
        put("JOURNAL_ENTRY_VALIDATED", "companion/application/CompanionService.java");
        put("JOURNAL_ENTRY_REJECTED", "companion/application/CompanionService.java");
        put("DELIVERABLE_VALIDATED", "companion/application/CompanionService.java");
        put("DELIVERABLE_REJECTED", "companion/application/CompanionService.java");
        put("DELIVERABLE_VERSION_UPLOADED", "companion/application/CompanionService.java");
        put("DELIVERABLE_VERSION_DOWNLOADED", "companion/application/CompanionService.java");
        // Certificate generation + downloads (CertificateService)
        put("CERTIFICATE_GENERATED", "certificate/application/CertificateService.java");
        put("CERTIFICATE_DOWNLOADED", "certificate/application/CertificateService.java");
        // Finance approval / rejection / receipt (FinanceService)
        put("FINANCE_CASE_APPROVED", "finance/application/FinanceService.java");
        put("FINANCE_CASE_REJECTED", "finance/application/FinanceService.java");
        put("PAYMENT_RECEIPT_ISSUED", "finance/application/FinanceService.java");
        // Restricted / sensitive document access (DocumentService)
        put("DOCUMENT_ACCESSED_RESTRICTED", "document/application/DocumentService.java");
        // Authentication / registration (role assignment surface) (AuthService)
        put("USER_REGISTERED", "iam/application/AuthService.java");
        put("USER_LOGIN", "iam/application/AuthService.java");
        put("ACCOUNT_LOCKED", "iam/application/AuthService.java");
        // AI recommendation human review (AiService)
        put("AI_RECOMMENDATION_REVIEWED", "ai/application/AiService.java");
        // Conversation membership changes (MessagingService — plan §1 requires these)
        put("CONVERSATION_MEMBER_ADDED", "messaging/application/MessagingService.java");
        put("CONVERSATION_MEMBER_REMOVED", "messaging/application/MessagingService.java");
        put("CONVERSATION_MEMBER_LEFT", "messaging/application/MessagingService.java");
        put("CONVERSATION_MEMBER_REJOINED", "messaging/application/MessagingService.java");
    }};

    @Test
    @DisplayName("every mandated audited action is still emitted by its responsible service via the audit writer")
    void requiredActionsAreAudited() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : ACTION_TO_SERVICE.entrySet()) {
            String action = entry.getKey();
            String file = sourceFile(entry.getValue());
            if (!file.contains("\"" + action + "\"")) {
                problems.add(action + " not found in " + entry.getValue());
            }
            if (!file.contains("auditService")) {
                problems.add(entry.getValue() + " does not wire the audit writer (auditService)");
            }
        }
        assertTrue(problems.isEmpty(),
                "Audit coverage checklist violations:\n  - " + String.join("\n  - ", problems));
    }

    private String sourceFile(String relative) throws IOException {
        Path p = Path.of("src/main/java/tn/steg/backend", relative);
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("Missing expected source file: " + p.toAbsolutePath());
        }
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}