package tn.steg.backend.ai.domain.client;

import java.util.Optional;

/**
 * Document-intelligence port (domain, E2/E5). Implemented by the Python FastAPI
 * adapter in infrastructure. All results are advisory; humans decide.
 */
public interface DocIntelClient {

    record DocumentValidation(boolean valid, boolean documentTypeValid, boolean candidateNameValid,
            double confidence, String reason) {}

    record ReportAnalysis(double relevanceScore, String detectedStegContent,
            String detectedActivities, String missingElements, String summary, String explanation,
            double confidence) {}

    record FinanceVerification(String verdict, String reasons, double confidence) {}

    /**
     * S7 structured verification (§7.3): one check per contract row, each
     * PASSED / FAILED / INCONCLUSIVE with expected / found / evidence.
     * {@code overall} is informational (PASS / FAIL / INCONCLUSIVE).
     */
    record VerificationCheck(String key, String label, String status,
            String expected, String found, String evidence) {}

    record VerificationResult(String overall, java.util.List<VerificationCheck> checks) {}

    record ReportExpected(String candidateName, String supervisorName, String internshipType,
            String startDate, String endDate, String universityName) {}

    record JournalExpected(String startDate, String endDate,
            int completedApprovedTasks, int totalTasks) {}

    Optional<DocumentValidation> validateDocument(byte[] pdf, String expectedType, String fullName);

    Optional<ReportAnalysis> analyzeReport(byte[] pdf, String applicationReference);

    Optional<FinanceVerification> verifyFinance(byte[] certificatePdf, byte[] reportPdf,
            String internFullName, String period, String internshipType);

    /**
     * S7 report verification (§7.1): the PDF plus the expected values read from
     * the database (python-ai never queries the DB). Empty when the service is
     * unconfigured, unreachable, or returns a non-conforming payload — callers
     * degrade to "AI unavailable", never block.
     */
    Optional<VerificationResult> verifyReport(byte[] pdf, ReportExpected expected);

    /**
     * S7 journal verification (§7.2): the PDF plus the period and the shared
     * task-completion ratio inputs (done = completed AND approved, denominator
     * excludes cancelled/deleted — one shared function).
     */
    Optional<VerificationResult> verifyJournal(byte[] pdf, JournalExpected expected);

    /** Whether the underlying provider is configured (non-empty baseUrl + token). */
    default boolean isConfigured() { return true; }

    /** Whether the circuit is open and calls should be short-circuited. */
    default boolean circuitOpen() { return false; }
}
