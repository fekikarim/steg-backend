package tn.steg.backend.reporting.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S11 Supervisor dashboard summary (AGENTS.md §8.3): statistics and quick
 * info for the caller's OWN candidates and tasks only, served by ONE scoped
 * backend call. Every map carries all enum values (zeros included) so counts
 * per status are exact.
 */
public record SupervisorDashboardSummaryDto(
        long myCandidates,
        Map<String, Long> candidatesByStatus,
        long tasksAwaitingApproval,
        long overdueTasks,
        long unreadNotifications,
        List<RecentNotificationDto> recentNotifications
) {
    public record RecentNotificationDto(
            UUID id,
            String title,
            Instant createdAt
    ) {
    }
}
