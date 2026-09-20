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

    Optional<DocumentValidation> validateDocument(byte[] pdf, String expectedType, String fullName);

    Optional<ReportAnalysis> analyzeReport(byte[] pdf, String applicationReference);

    Optional<FinanceVerification> verifyFinance(byte[] certificatePdf, byte[] reportPdf,
            String internFullName, String period, String internshipType);

    /** Whether the underlying provider is configured (non-empty baseUrl + token). */
    default boolean isConfigured() { return true; }

    /** Whether the circuit is open and calls should be short-circuited. */
    default boolean circuitOpen() { return false; }
}
