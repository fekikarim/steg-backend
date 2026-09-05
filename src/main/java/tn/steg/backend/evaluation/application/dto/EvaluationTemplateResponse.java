package tn.steg.backend.evaluation.application.dto;

import tn.steg.backend.evaluation.domain.model.EvaluationTemplate;

import java.time.Instant;
import java.util.UUID;

public record EvaluationTemplateResponse(
        UUID id,
        String name,
        String description,
        Boolean active,
        Integer versionNum,
        Instant createdAt,
        Instant updatedAt
) {
    public static EvaluationTemplateResponse from(EvaluationTemplate t) {
        return new EvaluationTemplateResponse(
                t.getId(),
                t.getName(),
                t.getDescription(),
                t.getActive(),
                t.getVersionNum(),
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }
}
