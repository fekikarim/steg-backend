package tn.steg.backend.companion.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.common.domain.event.TaskScheduledVisibleEvent;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.repository.TaskRepository;

import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * T04/D8 scheduled-visibility sweep: publishes one
 * {@link TaskScheduledVisibleEvent} per task whose moment has passed. The
 * notification fan-out is deduplicated per task, so repeated sweeps (and
 * restarts) never duplicate the alert; a task whose intern cannot be
 * resolved is skipped without failing the sweep.
 *
 * <p>Five minutes is deliberately coarse: appearing "on the chosen date" is
 * a day-granularity promise, and the student's board re-reads authoritative
 * state on every open regardless of the push.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskVisibilityScheduler {

    /** Bounded batch per sweep; the next sweep picks up the remainder. */
    static final int SWEEP_LIMIT = 200;

    private final TaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelayString = "${steg.tasks.visibility.fixed-delay-millis:300000}")
    @Transactional
    public void sweep() {
        try {
            List<Task> due = taskRepository.findScheduledDue(Instant.now(), PageRequest.of(0, SWEEP_LIMIT));
            for (Task task : due) {
                publishIfResolvable(task);
            }
            if (!due.isEmpty()) {
                log.info("Task visibility sweep: {} scheduled task(s) due", due.size());
            }
        } catch (Exception e) {
            log.error("Task visibility sweep failed: {}", e.getMessage());
        }
    }

    private void publishIfResolvable(Task task) {
        UUID internUserId = task.getInternship() != null
                && task.getInternship().getCandidate() != null
                && task.getInternship().getCandidate().getUser() != null
                ? task.getInternship().getCandidate().getUser().getId()
                : null;
        if (internUserId == null) {
            return;
        }
        UUID supervisorUserId = task.getCreatedBy() != null
                ? task.getCreatedBy().getId() : null;
        eventPublisher.publishEvent(new TaskScheduledVisibleEvent(
                task.getId(),
                task.getInternship() != null ? task.getInternship().getId() : null,
                task.getTitle(),
                internUserId,
                supervisorUserId));
    }
}
