package tn.steg.backend.internship.domain.service;

import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipType;

public record InternshipClassificationResult(
        InternshipType type,
        InternshipRequirement requirement,
        boolean paymentEligible,
        long durationInDays,
        String appliedRuleDescription
) {}
