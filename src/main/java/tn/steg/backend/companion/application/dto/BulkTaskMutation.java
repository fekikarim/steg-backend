package tn.steg.backend.companion.application.dto;

import java.util.UUID;

public record BulkTaskMutation(
        BulkTaskAction action,
        UUID internshipId,
        UUID taskId,
        TaskRequest task
) {
}