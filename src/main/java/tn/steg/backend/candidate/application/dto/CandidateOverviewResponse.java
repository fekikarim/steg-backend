package tn.steg.backend.candidate.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.certificate.domain.model.Certificate;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.document.domain.model.Document;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.finance.domain.model.PaymentReceipt;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.iam.domain.model.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Candidate detail aggregate (AGENTS.md §5.1): profile, supervisor,
 * applications, internship, task summary, documents and certificate/receipt
 * status in ONE backend response, so the detail screen performs a single call
 * instead of fanning out client-side.
 *
 * <p>The embedded {@code profile} follows the CIN rule of
 * {@link CandidateDetailResponse}: {@code nationalId} is present only for the
 * Admin, never for a Supervisor.
 */
@Schema(description = "Candidate detail aggregate (one backend call)")
public record CandidateOverviewResponse(

        CandidateDetailResponse profile,
        SupervisorInfo supervisor,
        List<ApplicationInfo> applications,
        InternshipInfo internship,
        TasksInfo tasks,
        List<DocumentInfo> documents,
        CertificateInfo certificate,
        ReceiptInfo receipt
) {

    @Schema(description = "Current supervisor of the candidate (user-backed: assignment or internship link)")
    public record SupervisorInfo(UUID userId, String email) {}

    @Schema(description = "Application summary row")
    public record ApplicationInfo(
            UUID id,
            String reference,
            ApplicationStatus status,
            InternshipType type,
            LocalDate submissionDate,
            Instant createdAt
    ) {}

    @Schema(description = "Latest internship of the candidate")
    public record InternshipInfo(
            UUID id,
            String reference,
            InternshipStatus status,
            InternshipType type,
            LocalDate startDate,
            LocalDate endDate
    ) {}

    @Schema(description = "Task summary across all internships of the candidate")
    public record TasksInfo(int total, Map<String, Long> byStatus) {}

    @Schema(description = "Linked document (application or internship source)")
    public record DocumentInfo(
            UUID id,
            String reference,
            DocumentType type,
            String source,
            boolean restrictedAccess,
            Instant createdAt
    ) {}

    @Schema(description = "Latest certificate of the candidate's internship, when any")
    public record CertificateInfo(
            UUID id,
            String reference,
            String status,
            LocalDate issueDate,
            Instant generatedAt
    ) {}

    @Schema(description = "Payment receipt issued for the candidate's internship, when any")
    public record ReceiptInfo(
            UUID id,
            String reference,
            String status,
            BigDecimal amount,
            String currencyCode,
            LocalDate paymentDate,
            Instant issuedAt
    ) {}

    public static SupervisorInfo supervisorOf(User supervisorUser) {
        return supervisorUser == null
                ? null
                : new SupervisorInfo(supervisorUser.getId(), supervisorUser.getEmail());
    }

    public static ApplicationInfo from(tn.steg.backend.application.domain.model.InternshipApplication a) {
        return new ApplicationInfo(
                a.getId(),
                a.getReference(),
                a.getStatus(),
                a.getCalculatedType(),
                a.getSubmissionDate(),
                a.getCreatedAt());
    }

    public static InternshipInfo from(Internship i) {
        return i == null
                ? null
                : new InternshipInfo(i.getId(), i.getReference(), i.getStatus(), i.getType(),
                        i.getStartDate(), i.getEndDate());
    }

    public static TasksInfo from(List<Task> tasks) {
        Map<String, Long> byStatus = tasks.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        t -> t.getStatus() == null ? TaskStatus.TODO.name() : t.getStatus().name(),
                        java.util.stream.Collectors.counting()));
        return new TasksInfo(tasks.size(), byStatus);
    }

    public static DocumentInfo from(Document d, String source) {
        return new DocumentInfo(
                d.getId(),
                d.getReference(),
                d.getType(),
                source,
                Boolean.TRUE.equals(d.getRestrictedAccess()),
                d.getCreatedAt());
    }

    public static CertificateInfo from(Certificate c) {
        return c == null
                ? null
                : new CertificateInfo(c.getId(), c.getReference(),
                        c.getStatus() != null ? c.getStatus().name() : null,
                        c.getIssueDate(), c.getGeneratedAt());
    }

    public static ReceiptInfo from(PaymentReceipt r) {
        return r == null
                ? null
                : new ReceiptInfo(r.getId(), r.getReference(),
                        r.getStatus() != null ? r.getStatus().name() : null,
                        r.getAmount(), r.getCurrencyCode(), r.getPaymentDate(), r.getIssuedAt());
    }
}
