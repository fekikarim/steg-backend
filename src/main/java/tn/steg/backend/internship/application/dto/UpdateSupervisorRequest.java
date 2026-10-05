package tn.steg.backend.internship.application.dto;

import tn.steg.backend.iam.domain.model.UserStatus;

public record UpdateSupervisorRequest(
        UserStatus status,
        Boolean enabled
) {
}
