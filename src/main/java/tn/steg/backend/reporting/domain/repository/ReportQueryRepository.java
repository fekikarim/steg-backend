package tn.steg.backend.reporting.domain.repository;

import tn.steg.backend.reporting.domain.model.GroupCount;
import tn.steg.backend.reporting.domain.model.PaymentTotalRow;
import tn.steg.backend.reporting.domain.model.WorkloadRow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only reporting port. Every method performs a database-side aggregate —
 * no raw entity loading, no N+1 — and none of them may mutate state. See the
 * JPA adapter for the concrete queries.
 */
public interface ReportQueryRepository {

    List<GroupCount> countApplicationsByStatus();

    List<GroupCount> countInternshipsByType();

    List<GroupCount> countInternshipsByStatus();

    List<GroupCount> countInternshipsByDepartment();

    List<GroupCount> countFinanceCasesByStatus();

    List<PaymentTotalRow> paymentTotals();

    List<PaymentTotalRow> paymentTotalsForDepartment(UUID departmentId);

    /**
     * S11 dashboard summaries (AGENTS.md §8.3). Same aggregate discipline:
     * single GROUP BY / COUNT statements, soft-deleted candidates excluded
     * everywhere via the live-candidate guard, scoped variants filter on the
     * caller's supervised candidate ids (resolved once by the service through
     * {@code SupervisionScopeService}, never per row).
     */
    List<GroupCount> countTasksByStatus();

    List<GroupCount> countTasksByStatusForCandidates(List<UUID> candidateIds);

    long countOverdueTasksForCandidates(List<UUID> candidateIds, LocalDate today);

    long countOverdueTasksGlobal(LocalDate today);

    long countLiveCandidatesCreatedSince(Instant since);

    long countLiveCandidates();

    List<GroupCount> countInternshipsByStatusForCandidates(List<UUID> candidateIds);

    long countPaymentReceipts();

    long countIssuedCertificates();

    List<WorkloadRow> workloadBySupervisor();

    long countApplicationsCreatedBetween(Instant fromInclusive, Instant toExclusive);

    long countInternshipsCreatedBetween(Instant fromInclusive, Instant toExclusive);
}