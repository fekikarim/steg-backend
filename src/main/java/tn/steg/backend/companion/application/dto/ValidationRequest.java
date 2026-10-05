package tn.steg.backend.companion.application.dto;

public record ValidationRequest(
                Boolean approve,
        String comment
) {
        public ValidationRequest {
                approve = Boolean.TRUE.equals(approve);
        }

        public ValidationRequest(String comment) {
                this(false, comment);
        }
}
