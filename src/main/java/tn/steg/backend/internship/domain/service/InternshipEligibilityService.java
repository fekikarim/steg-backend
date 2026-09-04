package tn.steg.backend.internship.domain.service;

import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipRequirement;

import java.util.Objects;

/**
 * Domain service providing predicate logic for payment eligibility.
 * Only OBLIGATOIRE internships are eligible to reach the Finance workflow.
 */
public class InternshipEligibilityService {

    public boolean isPaymentEligible(Internship internship) {
        Objects.requireNonNull(internship, "internship must not be null");
        return internship.getRequirement() == InternshipRequirement.OBLIGATOIRE;
    }
}
