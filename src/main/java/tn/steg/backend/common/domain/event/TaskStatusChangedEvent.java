package tn.steg.backend.common.domain.event;

import tn.steg.backend.companion.domain.model.TaskStatus;

import java.time.Instant;
import java.util.UUID;

public record TaskStatusChangedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorId,
        UUID taskId,
        UUID internshipId,
        String taskTitle,
        TaskStatus status,
        UUID supervisorUserId,
        UUID internUserId
) implements DomainEvent {

    public TaskStatusChangedEvent(UUID taskId, UUID internshipId, String taskTitle,
                                  TaskStatus status, UUID supervisorUserId,
                                  UUID internUserId, UUID actorId) {
        this(UUID.randomUUID(), Instant.now(), actorId, taskId, internshipId,
                taskTitle, status, supervisorUserId, internUserId);
    }
}