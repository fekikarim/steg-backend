package tn.steg.backend.companion.application.dto;

import tn.steg.backend.companion.domain.model.TaskStatus;

import java.time.LocalDate;
import java.util.UUID;

public record TaskRequest(
        String title,
        String description,
        UUID assignedToId,
        LocalDate dueDate,
        TaskStatus status
) {}
