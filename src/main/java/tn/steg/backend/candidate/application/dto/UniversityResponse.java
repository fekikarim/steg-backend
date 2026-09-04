package tn.steg.backend.candidate.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.candidate.domain.model.University;

import java.util.UUID;

@Schema(description = "University reference data")
public record UniversityResponse(

        @Schema(description = "University UUID")
        UUID id,

        @Schema(description = "Short code")
        String code,

        @Schema(description = "Full name")
        String name,

        @Schema(description = "Whether the university is active")
        Boolean active
) {
    public static UniversityResponse from(University u) {
        return new UniversityResponse(u.getId(), u.getCode(), u.getName(), u.getActive());
    }
}
