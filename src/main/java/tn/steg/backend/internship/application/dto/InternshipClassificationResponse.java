package tn.steg.backend.internship.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.domain.service.InternshipClassificationResult;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Read-only transparency representation of computed internship classification and applied rules")
public record InternshipClassificationResponse(
        UUID internshipId,
        String reference,
        LocalDate startDate,
        LocalDate endDate,
        InternshipType type,
        InternshipRequirement requirement,
        boolean paymentEligible,
        long durationInDays,
        String appliedRuleDescription
) {
    public static InternshipClassificationResponse from(
            UUID internshipId,
            String reference,
            LocalDate startDate,
            LocalDate endDate,
            InternshipClassificationResult result
    ) {
        return new InternshipClassificationResponse(
                internshipId,
                reference,
                startDate,
                endDate,
                result.type(),
                result.requirement(),
                result.paymentEligible(),
                result.durationInDays(),
                result.appliedRuleDescription()
        );
    }
}
