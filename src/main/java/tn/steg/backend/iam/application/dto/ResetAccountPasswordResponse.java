package tn.steg.backend.iam.application.dto;

public record ResetAccountPasswordResponse(
        String email,
        String temporaryPassword,
        boolean emailSent
) {
}
