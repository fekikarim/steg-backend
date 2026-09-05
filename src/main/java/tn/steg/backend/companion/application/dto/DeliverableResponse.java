package tn.steg.backend.companion.application.dto;

import tn.steg.backend.companion.domain.model.Deliverable;
import tn.steg.backend.companion.domain.model.DeliverableStatus;

import java.time.Instant;
import java.util.UUID;

public record DeliverableResponse(
        UUID id,
        UUID internshipId,
        UUID validatedById,
        String validatedByName,
        String title,
        String description,
        DeliverableStatus status,
        Integer currentVersion,
        DeliverableVersionResponse latestVersion,
        Instant submittedAt,
        Instant validatedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static DeliverableResponse from(Deliverable deliverable, DeliverableVersionResponse latestVersion) {
        UUID validatedById = deliverable.getValidatedBy() != null ? deliverable.getValidatedBy().getId() : null;
        String validatedByName = null;
        if (deliverable.getValidatedBy() != null) {
            validatedByName = deliverable.getValidatedBy().getFirstName() + " " + deliverable.getValidatedBy().getLastName();
        }

        return new DeliverableResponse(
                deliverable.getId(),
                deliverable.getInternship() != null ? deliverable.getInternship().getId() : null,
                validatedById,
                validatedByName,
                deliverable.getTitle(),
                deliverable.getDescription(),
                deliverable.getStatus(),
                deliverable.getCurrentVersion(),
                latestVersion,
                deliverable.getSubmittedAt(),
                deliverable.getValidatedAt(),
                deliverable.getCreatedAt(),
                deliverable.getUpdatedAt()
        );
    }
}
