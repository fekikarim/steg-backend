package tn.steg.backend.companion.application.dto;

import tn.steg.backend.companion.domain.model.TaskStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TaskRequest(
        String title,
        String description,
        UUID assignedToId,
        LocalDate dueDate,
        TaskStatus status,
        /**
         * T04/D8 scheduled visibility: null on create = visible immediately;
         * null on update = leave unchanged (send a past instant to make a
         * scheduled task immediate). Staff-only write — an intern sending a
         * non-null value is refused (TASK_SCHEDULE_STAFF_ONLY).
         */
        Instant visibleFrom
) {
    /** Legacy shape (pre-T04): no scheduling requested. */
    public TaskRequest(
            String title,
            String description,
            UUID assignedToId,
            LocalDate dueDate,
            TaskStatus status) {
        this(title, description, assignedToId, dueDate, status, null);
    }
}
