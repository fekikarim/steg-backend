package tn.steg.backend.internship.application.dto;

import tn.steg.backend.internship.domain.model.ValidationDecision;
import tn.steg.backend.internship.domain.model.ValidationDocumentType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * S7 validation detail (§5.11 step 2): everything the Admin needs on one
 * screen — candidate, tasks with statuses (+ the shared completion ratio),
 * the report and journal documents, the latest AI run and manual decision per
 * document, and the receipt state.
 */
public record ValidationDetailResponse(
        UUID internshipId,
        String reference,
        String status,
        String type,
        String requirement,
        String startDate,
        String endDate,
        CandidateInfo candidate,
        SupervisorInfo supervisor,
        TaskSummary tasks,
        DocumentRef report,
        DocumentRef journal,
        RunView latestReportRun,
        RunView latestJournalRun,
        DecisionView reportDecision,
        DecisionView journalDecision,
        ReceiptInfo receipt
) {
    public record CandidateInfo(UUID id, String fullName, String email, String universityName) {
    }

    public record SupervisorInfo(UUID userId, String email, String displayName) {
    }

    public record TaskSummary(int total, int done, List<TaskItem> items) {
    }

    public record TaskItem(UUID id, String title, String status) {
    }

    /** A validation document: the submitted deliverable behind it (assumption #18). */
    public record DocumentRef(
            String documentType,
            UUID deliverableId,
            UUID fileAssetId,
            Integer version,
            String title,
            Instant submittedAt,
            Long sizeBytes
    ) {
    }

    public record CheckView(String key, String label, String status,
            String expected, String found, String evidence) {
    }

    public record RunView(String overall, boolean degraded, List<CheckView> checks,
            UUID runBy, Instant runAt) {
    }

    public record DecisionView(String decision, String comment, UUID decidedBy, Instant decidedAt) {
    }

    public record ReceiptInfo(String reference, String amount, String currency, boolean downloadable) {
    }

    /** Manual decision command (§5.11 step 4): REJECTED requires a comment. */
    public record DecideCommand(ValidationDocumentType documentType, ValidationDecision decision, String comment) {
    }
}
