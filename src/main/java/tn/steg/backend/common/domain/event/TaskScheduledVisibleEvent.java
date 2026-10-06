package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * T04/D8: published once per task when its scheduled moment passes — the
 * student learns that a task scheduled by the supervisor is now on his
 * board. The scheduler sweep publishes it; the notification fan-out is
 * deduplicated per task ({@code SCHEDULED_TASK_VISIBLE:<taskId>}), so
 * repeated sweeps never duplicate the alert.
 */
public record TaskScheduledVisibleEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID taskId,
        UUID internshipId,
        String taskTitle,
        UUID internUserId,
        UUID supervisorUserId
) implements DomainEvent {

    public TaskScheduledVisibleEvent(UUID taskId, UUID internshipId, String taskTitle,
                                     UUID internUserId, UUID supervisorUserId) {
        this(UUID.randomUUID(), Instant.now(), supervisorUserId, taskId, internshipId,
                taskTitle, internUserId, supervisorUserId);
    }
}
