package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Published when a task's editable fields are changed (title, description, due date, assignee). */
public record TaskUpdatedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID taskId,
        UUID internshipId,
        String taskTitle,
        UUID supervisorUserId,
        UUID internUserId
) implements DomainEvent {

    public TaskUpdatedEvent(UUID taskId, UUID internshipId, String taskTitle,
                            UUID supervisorUserId, UUID internUserId, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId, taskId, internshipId,
                taskTitle, supervisorUserId, internUserId);
    }
}
