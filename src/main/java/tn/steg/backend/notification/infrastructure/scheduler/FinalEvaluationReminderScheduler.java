package tn.steg.backend.notification.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.evaluation.domain.model.EvaluationType;
import tn.steg.backend.evaluation.domain.repository.EvaluationDomainRepository;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.notification.application.NotificationService;
import tn.steg.backend.notification.domain.model.NotificationPriority;

import java.util.List;
import java.util.UUID;

/**
 * Self-healing safety net for the "an intern ended without a final evaluation"
 * alert. The completion event covers new cases in real time; this sweep
 * re-derives the same condition periodically so nothing is ever missed:
 * internships completed before the alert existed, a deleted FINAL report, a
 * replayed event, or a service that was down at completion time.
 *
 * <p>Idempotent by construction — the dispatch carries the same dedupe key as
 * the event path, so a compliant internship costs one indexed read and
 * nothing else. Bounded, read-only, and never fatal: any failure is logged and
 * retried on the next tick.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinalEvaluationReminderScheduler {

    private final InternshipRepository internshipRepository;
    private final EvaluationDomainRepository evaluationRepository;
    private final NotificationService notificationService;

    @Value("${steg.notifications.evaluation-reminder.enabled:true}")
    private boolean enabled = true;

    @Value("${steg.notifications.evaluation-reminder.batch-size:200}")
    private int batchSize = 200;

    @Scheduled(fixedDelayString = "${steg.notifications.evaluation-reminder.fixed-delay-millis:3600000}",
            initialDelayString = "${steg.notifications.evaluation-reminder.initial-delay-millis:60000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            List<Internship> completed = internshipRepository.findByStatus(InternshipStatus.VALIDATED);
            if (completed.isEmpty()) {
                return;
            }
            int limit = batchSize > 0 ? batchSize : completed.size();
            int pending = 0;
            for (Internship internship : completed.subList(0, Math.min(limit, completed.size()))) {
                if (hasFinalEvaluation(internship.getId())) {
                    continue;
                }
                notificationService.dispatchToRole(
                        NotificationService.FINAL_EVALUATION_DEDUPE_PREFIX + internship.getId(),
                        NotificationService.ADMIN_ROLE_CODE,
                        NotificationService.FINAL_EVALUATION_TITLE,
                        "Internship " + internship.getReference() + " (" + candidateName(internship) + ")"
                                + " reached COMPLETED without a final evaluation. "
                                + "Record the FINAL report to unblock administrative validation.",
                        NotificationPriority.HIGH,
                        "Internship", internship.getId(),
                        null);
                pending++;
            }
            if (pending > 0) {
                log.info("Final-evaluation sweep: {} completed internship(s) still awaiting a FINAL report",
                        pending);
            }
        } catch (Exception e) {
            log.error("Final-evaluation sweep failed: {}", e.getMessage());
        }
    }

    private boolean hasFinalEvaluation(UUID internshipId) {
        return !evaluationRepository.findByInternshipIdAndType(internshipId, EvaluationType.FINAL).isEmpty();
    }

    private String candidateName(Internship internship) {
        Candidate candidate = internship.getCandidate();
        if (candidate == null) {
            return "";
        }
        return (candidate.getFirstName() + " " + candidate.getLastName()).strip();
    }
}
