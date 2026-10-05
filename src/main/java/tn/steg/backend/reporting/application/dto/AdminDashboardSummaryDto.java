package tn.steg.backend.reporting.application.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S11 Admin dashboard summary (AGENTS.md §8.3): global statistics and quick
 * actions for every module, served by ONE scoped backend call. Every map
 * carries all enum values (zeros included) so counts per status are exact.
 */
public record AdminDashboardSummaryDto(
        Map<String, Long> applicationsByStatus,
        long newCandidates,
        long tasksAwaitingApproval,
        Map<String, Long> reportsAwaitingValidation,
        long receiptsIssued,
        long certificatesIssued,
        List<WorkloadRowDto> supervisorWorkload,
        List<TrendPointDto> trends
) {
    /** Candidates per supervising user (the Admin included when supervising). */
    public record WorkloadRowDto(
            UUID supervisorUserId,
            String email,
            long candidateCount
    ) {
    }

    /** One calendar month in the application time zone. */
    public record TrendPointDto(
            String month,
            long applications,
            long internships
    ) {
    }
}
