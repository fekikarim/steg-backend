package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Published when a task is created with (or reassigned to) an assignee. */
public record TaskAssignedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID taskId,
        String taskTitle,
        UUID internshipId,
        UUID assigneeUserId
) implements DomainEvent {

    public TaskAssignedEvent(UUID taskId, String taskTitle, UUID internshipId,
                             UUID assigneeUserId, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId,
                taskId, taskTitle, internshipId, assigneeUserId);
    }
}
