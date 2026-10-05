package tn.steg.backend.internship.application.dto;

import jakarta.validation.constraints.NotNull;
import tn.steg.backend.internship.domain.model.InternshipStatus;

/**
 * Request for {@code POST /api/internships/{id}/status-transitions}: move an
 * internship to the next explicit status of the AGENTS.md §4 model.
 *
 * @param targetStatus the requested next status (never a free-form value)
 * @param comment      optional note stored in the audit trail
 */
public record InternshipStatusTransitionRequest(
        @NotNull(message = "targetStatus is required")
        InternshipStatus targetStatus,
        String comment
) {
}
