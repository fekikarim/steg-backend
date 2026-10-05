package tn.steg.backend.reporting.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.common.application.ApplicationTimeZone;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.internship.application.SupervisionScopeService;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.notification.domain.repository.NotificationDeliveryRepository;
import tn.steg.backend.reporting.application.dto.AdminDashboardSummaryDto;
import tn.steg.backend.reporting.application.dto.SupervisorDashboardSummaryDto;
import tn.steg.backend.reporting.domain.model.GroupCount;
import tn.steg.backend.reporting.domain.repository.ReportQueryRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S11 dashboard summaries (AGENTS.md §8.3): dedicated scoped backend summary
 * endpoints. The client never computes numbers from paged lists — every tile
 * renders one of these server-side aggregates and deep-links to the filtered
 * list that the same backend scope would return.
 *
 * <p>Decisions recorded in audit assumption S11: "new candidates" counts live
 * profiles created in the last 30 days; "tasks awaiting approval" are
 * COMPLETED tasks; "overdue" means a due date before today (application time
 * zone) on a non-terminal task; "certificates issued" counts generated,
 * non-revoked certificates (audit assumption #33);
 * trends bucket creations into the last 6 calendar months (inclusive of the
 * current one) resolved in the application time zone; an Admin calling the
 * supervisor summary sees global numbers (ADMIN ⊇ SUPERVISOR, §3.2).
 */
@Service
@RequiredArgsConstructor
public class DashboardSummaryService {

    private static final int NEW_CANDIDATE_DAYS = 30;
    private static final int TREND_MONTHS = 6;

    private final ReportQueryRepository reportQueryRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final SupervisionScopeService supervisionScopeService;
    private final ApplicationTimeZone applicationTimeZone;

    @Transactional(readOnly = true)
    public AdminDashboardSummaryDto adminSummary() {
        Map<String, Long> applications = fullMap(ApplicationStatus.values(),
                reportQueryRepository.countApplicationsByStatus());
        long newCandidates = reportQueryRepository.countLiveCandidatesCreatedSince(
                Instant.now().minus(NEW_CANDIDATE_DAYS, ChronoUnit.DAYS));
        Map<String, Long> tasks = fullMap(TaskStatus.values(),
                reportQueryRepository.countTasksByStatus());
        Map<String, Long> internships = fullMap(InternshipStatus.values(),
                reportQueryRepository.countInternshipsByStatus());

        List<AdminDashboardSummaryDto.WorkloadRowDto> workload = reportQueryRepository
                .workloadBySupervisor().stream()
                .map(r -> new AdminDashboardSummaryDto.WorkloadRowDto(
                        r.getSupervisorUserId(), r.getEmail(), r.getCandidateCount()))
                .toList();

        return new AdminDashboardSummaryDto(
                applications,
                newCandidates,
                tasks.getOrDefault(TaskStatus.COMPLETED.name(), 0L),
                internships,
                reportQueryRepository.countPaymentReceipts(),
                reportQueryRepository.countIssuedCertificates(),
                workload,
                trends());
    }

    @Transactional(readOnly = true)
    public SupervisorDashboardSummaryDto supervisorSummary(UserPrincipal actor) {
        List<UUID> scope = supervisionScopeService.hasGlobalAccess(actor)
                ? null
                : supervisionScopeService.supervisedCandidateIds(actor);
        if (scope != null && scope.isEmpty()) {
            return zeroSupervisorSummary(actor);
        }

        Map<String, Long> tasks = scope == null
                ? fullMap(TaskStatus.values(), reportQueryRepository.countTasksByStatus())
                : fullMap(TaskStatus.values(),
                        reportQueryRepository.countTasksByStatusForCandidates(scope));
        Map<String, Long> internships = scope == null
                ? fullMap(InternshipStatus.values(), reportQueryRepository.countInternshipsByStatus())
                : fullMap(InternshipStatus.values(),
                        reportQueryRepository.countInternshipsByStatusForCandidates(scope));
        long overdue = scope == null
                ? reportQueryRepository.countOverdueTasksGlobal(applicationTimeZone.today())
                : reportQueryRepository.countOverdueTasksForCandidates(scope, applicationTimeZone.today());
        long myCandidates = scope == null
                ? reportQueryRepository.countLiveCandidates()
                : scope.size();
        long unread = deliveryRepository.countUnreadInAppByRecipientId(actor.getId());
        List<SupervisorDashboardSummaryDto.RecentNotificationDto> recent = deliveryRepository
                .findInAppByRecipientId(actor.getId(), PageRequest.of(0, 5)).stream()
                .map(d -> new SupervisorDashboardSummaryDto.RecentNotificationDto(
                        d.getNotification().getId(),
                        d.getNotification().getTitle(),
                        d.getCreatedAt()))
                .toList();

        return new SupervisorDashboardSummaryDto(
                myCandidates,
                internships,
                tasks.getOrDefault(TaskStatus.COMPLETED.name(), 0L),
                overdue,
                unread,
                recent);
    }

    private SupervisorDashboardSummaryDto zeroSupervisorSummary(UserPrincipal actor) {
        long unread = deliveryRepository.countUnreadInAppByRecipientId(actor.getId());
        List<SupervisorDashboardSummaryDto.RecentNotificationDto> recent = deliveryRepository
                .findInAppByRecipientId(actor.getId(), PageRequest.of(0, 5)).stream()
                .map(d -> new SupervisorDashboardSummaryDto.RecentNotificationDto(
                        d.getNotification().getId(),
                        d.getNotification().getTitle(),
                        d.getCreatedAt()))
                .toList();
        return new SupervisorDashboardSummaryDto(
                0L,
                fullMap(InternshipStatus.values(), List.of()),
                0L,
                0L,
                unread,
                recent);
    }

    private List<AdminDashboardSummaryDto.TrendPointDto> trends() {
        ZoneId zone = applicationTimeZone.zoneId();
        YearMonth current = YearMonth.now(zone);
        List<AdminDashboardSummaryDto.TrendPointDto> points = new ArrayList<>();
        for (int back = TREND_MONTHS - 1; back >= 0; back--) {
            YearMonth month = current.minusMonths(back);
            Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
            Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
            points.add(new AdminDashboardSummaryDto.TrendPointDto(
                    month.toString(),
                    reportQueryRepository.countApplicationsCreatedBetween(from, to),
                    reportQueryRepository.countInternshipsCreatedBetween(from, to)));
        }
        return points;
    }

    private static <E extends Enum<E>> Map<String, Long> fullMap(E[] values, List<GroupCount> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (E value : values) {
            counts.put(value.name(), 0L);
        }
        for (GroupCount row : rows) {
            if (row.getGroupName() != null && counts.containsKey(row.getGroupName())) {
                counts.put(row.getGroupName(), row.getCount());
            }
        }
        return counts;
    }
}
