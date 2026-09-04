package tn.steg.backend.internship.domain.service;

import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Pure, deterministic domain service responsible for computing the internship type
 * and requirement classification strictly from the internship's date boundaries.
 *
 * Business rules (STEG specification):
 * - Duration ≈ 1 month (< 42 days / 6 weeks):
 *     → InternshipType.OBSERVATION
 *     → Requirement: explicit staff flag (OBLIGATOIRE or OPTIONAL).
 *       If unspecified (null), logs a warning and conservatively defaults to OPTIONAL
 *       (so no unintended payment eligibility occurs).
 * - Duration between 6 weeks (inclusive, >= 42 days) and 3 months (inclusive, <= 3 calendar months / <= 92 days):
 *     → InternshipType.PERFECTIONNEMENT
 *     → Requirement forced to OBLIGATOIRE.
 * - Duration > 3 months:
 *     → InternshipType.PFE
 *     → Requirement forced to OBLIGATOIRE.
 *
 * This service is pure, framework-free, and unit testable independent of Spring.
 */
public class InternshipClassificationService {

    private static final Logger LOGGER = Logger.getLogger(InternshipClassificationService.class.getName());

    public InternshipClassificationResult classify(LocalDate startDate, LocalDate endDate, Boolean observationObligatoire) {
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");

        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate (" + endDate + ") cannot be before startDate (" + startDate + ")");
        }

        // Days inclusive of start and end date (+1 to count both bounding days)
        long durationInDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        Period period = Period.between(startDate, endDate);

        // A duration strictly greater than 3 months:
        // Either period.getYears() > 0, or period.getMonths() > 3, or (period.getMonths() == 3 and period.getDays() > 0)
        boolean isGreaterThan3Months = period.getYears() > 0
                || period.getMonths() > 3
                || (period.getMonths() == 3 && period.getDays() > 0);

        // If calendar months arithmetic shows <= 3 months but days count boundary:
        // 6 weeks = 42 days.
        if (durationInDays < 42) {
            InternshipRequirement req;
            if (observationObligatoire != null) {
                req = observationObligatoire ? InternshipRequirement.OBLIGATOIRE : InternshipRequirement.OPTIONAL;
            } else {
                LOGGER.warning("TODO — STEG VALIDATION REQUIRED: Observation internship requirement unspecified. Defaulting conservatively to OPTIONAL.");
                req = InternshipRequirement.OPTIONAL;
            }
            boolean eligible = req == InternshipRequirement.OBLIGATOIRE;
            return new InternshipClassificationResult(
                    InternshipType.OBSERVATION,
                    req,
                    eligible,
                    durationInDays,
                    "Duration < 6 weeks (" + durationInDays + " days) classified as OBSERVATION; requirement=" + req
            );
        } else if (!isGreaterThan3Months) {
            return new InternshipClassificationResult(
                    InternshipType.PERFECTIONNEMENT,
                    InternshipRequirement.OBLIGATOIRE,
                    true,
                    durationInDays,
                    "Duration between 6 weeks and 3 months (" + durationInDays + " days) classified as PERFECTIONNEMENT (OBLIGATOIRE)"
            );
        } else {
            return new InternshipClassificationResult(
                    InternshipType.PFE,
                    InternshipRequirement.OBLIGATOIRE,
                    true,
                    durationInDays,
                    "Duration > 3 months (" + durationInDays + " days) classified as PFE (OBLIGATOIRE)"
            );
        }
    }
}
