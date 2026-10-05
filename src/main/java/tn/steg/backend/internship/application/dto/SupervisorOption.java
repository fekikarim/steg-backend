package tn.steg.backend.internship.application.dto;

import tn.steg.backend.iam.domain.model.User;

import java.util.UUID;

public record SupervisorOption(UUID userId, String email, String role) {
    public static SupervisorOption from(User user, String role) {
        return new SupervisorOption(user.getId(), user.getEmail(), role);
    }
}