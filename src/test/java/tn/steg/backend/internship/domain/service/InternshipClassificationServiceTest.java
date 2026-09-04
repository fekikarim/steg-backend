package tn.steg.backend.internship.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InternshipClassificationService (Deterministic Engine Tests)")
class InternshipClassificationServiceTest {

    private InternshipClassificationService classificationService;

    @BeforeEach
    void setUp() {
        classificationService = new InternshipClassificationService();
    }

    @Nested
    @DisplayName("Invalid Boundaries")
    class InvalidBoundaries {
        @Test
        @DisplayName("Should throw IllegalArgumentException when endDate is before startDate")
        void endDateBeforeStartDate() {
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 5, 31);

            assertThatThrownBy(() -> classificationService.classify(start, end, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Boundary: < 6 weeks (Observation ≈ 1 month)")
    class ObservationTests {

        @Test
        @DisplayName("Duration exactly 1 month (30 days in June): OBSERVATION")
        void exactlyOneMonth() {
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 6, 30); // 30 days

            InternshipClassificationResult result = classificationService.classify(start, end, true);

            assertThat(result.type()).isEqualTo(InternshipType.OBSERVATION);
            assertThat(result.requirement()).isEqualTo(InternshipRequirement.OBLIGATOIRE);
            assertThat(result.paymentEligible()).isTrue();
            assertThat(result.durationInDays()).isEqualTo(30);
        }

        @Test
        @DisplayName("Duration 41 days (under 6 weeks): OBSERVATION, defaults to OPTIONAL when unspecified")
        void fortyOneDaysDefaultsToOptional() {
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = start.plusDays(40); // 41 days inclusive

            InternshipClassificationResult result = classificationService.classify(start, end, null);

            assertThat(result.type()).isEqualTo(InternshipType.OBSERVATION);
            assertThat(result.requirement()).isEqualTo(InternshipRequirement.OPTIONAL);
            assertThat(result.paymentEligible()).isFalse();
            assertThat(result.durationInDays()).isEqualTo(41);
        }

        @Test
        @DisplayName("Duration 41 days: OBSERVATION, explicitly set to OPTIONAL")
        void observationExplicitlyOptional() {
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = start.plusDays(40);

            InternshipClassificationResult result = classificationService.classify(start, end, false);

            assertThat(result.type()).isEqualTo(InternshipType.OBSERVATION);
            assertThat(result.requirement()).isEqualTo(InternshipRequirement.OPTIONAL);
            assertThat(result.paymentEligible()).isFalse();
        }
    }

    @Nested
    @DisplayName("Boundary: 6 weeks to 3 months (Perfectionnement)")
    class PerfectionnementTests {

        @Test
        @DisplayName("Duration exactly 6 weeks (42 days): PERFECTIONNEMENT forced to OBLIGATOIRE")
        void exactlySixWeeks() {
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = start.plusDays(41); // 42 days inclusive

            InternshipClassificationResult result = classificationService.classify(start, end, null);

            assertThat(result.type()).isEqualTo(InternshipType.PERFECTIONNEMENT);
            assertThat(result.requirement()).isEqualTo(InternshipRequirement.OBLIGATOIRE);
            assertThat(result.paymentEligible()).isTrue();
            assertThat(result.durationInDays()).isEqualTo(42);
        }

        @Test
        @DisplayName("Duration exactly 3 calendar months (June 1 to Sept 1): PERFECTIONNEMENT")
        void exactlyThreeMonths() {
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 9, 1);

            InternshipClassificationResult result = classificationService.classify(start, end, null);

            assertThat(result.type()).isEqualTo(InternshipType.PERFECTIONNEMENT);
            assertThat(result.requirement()).isEqualTo(InternshipRequirement.OBLIGATOIRE);
            assertThat(result.paymentEligible()).isTrue();
        }
    }

    @Nested
    @DisplayName("Boundary: > 3 months (PFE)")
    class PfeTests {

        @Test
        @DisplayName("Duration 3 months + 1 day: PFE forced to OBLIGATOIRE")
        void threeMonthsPlusOneDay() {
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 9, 2); // 3 months + 1 day

            InternshipClassificationResult result = classificationService.classify(start, end, null);

            assertThat(result.type()).isEqualTo(InternshipType.PFE);
            assertThat(result.requirement()).isEqualTo(InternshipRequirement.OBLIGATOIRE);
            assertThat(result.paymentEligible()).isTrue();
        }

        @Test
        @DisplayName("Duration 6 months: PFE forced to OBLIGATOIRE")
        void sixMonths() {
            LocalDate start = LocalDate.of(2026, 2, 1);
            LocalDate end = LocalDate.of(2026, 8, 1);

            InternshipClassificationResult result = classificationService.classify(start, end, null);

            assertThat(result.type()).isEqualTo(InternshipType.PFE);
            assertThat(result.requirement()).isEqualTo(InternshipRequirement.OBLIGATOIRE);
            assertThat(result.paymentEligible()).isTrue();
        }
    }
}
