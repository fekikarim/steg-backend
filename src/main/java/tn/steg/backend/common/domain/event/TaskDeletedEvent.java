package tn.steg.backend.common.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after a task is deleted (single delete and bulk delete) so the
 * affected intern learns why the task vanished from their board. The title is
 * carried in the event because the entity no longer exists afterwards.
 */
public record TaskDeletedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID taskId,
        UUID internshipId,
        String taskTitle,
        UUID supervisorUserId,
        UUID internUserId
) implements DomainEvent {

    public TaskDeletedEvent(UUID taskId, UUID internshipId, String taskTitle,
                            UUID supervisorUserId, UUID internUserId, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId, taskId, internshipId,
                taskTitle, supervisorUserId, internUserId);
    }
}
