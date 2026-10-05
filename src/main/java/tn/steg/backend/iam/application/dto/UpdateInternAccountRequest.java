package tn.steg.backend.iam.application.dto;

import tn.steg.backend.iam.domain.model.UserStatus;

public record UpdateInternAccountRequest(
        UserStatus status,
        Boolean enabled
) {
}
