package tn.steg.backend.finance.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive unit tests for the deterministic payment math (Phase A11).
 * Pure function: no Spring, no clock, no I/O. Defaults under test:
 * rate 50 TND, max 3 payable months, max 150 TND, currency TND.
 */
@DisplayName("PaymentCalculationService unit tests (deterministic math)")
class PaymentCalculationServiceTest {

    private static final BigDecimal RATE_50 = new BigDecimal("50.00");
    private static final BigDecimal MAX_150 = new BigDecimal("150.00");

    private PaymentCalculationResult calc(LocalDate start, LocalDate end) {
        return PaymentCalculationService.calculate(start, end, RATE_50, 3, MAX_150, "TND");
    }

    @Test
    @DisplayName("29 days yield 0 completed months (no prorating, ever)")
    void twentyNineDaysYieldZero() {
        PaymentCalculationResult result = calc(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 30));
        assertThat(result.completedMonths()).isEqualTo(0);
        assertThat(result.payableMonths()).isEqualTo(0);
        assertThat(result.cappedAmount()).isEqualByComparingTo("0.00");
        assertThat(result.capApplied()).isFalse();
    }

    @Test
    @DisplayName("Exactly 1 month yields 1 completed month")
    void exactlyOneMonth() {
        PaymentCalculationResult result = calc(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
        assertThat(result.completedMonths()).isEqualTo(1);
        assertThat(result.payableMonths()).isEqualTo(1);
        assertThat(result.cappedAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("1 month + 1 day still yields 1 completed month (partial never counts)")
    void oneMonthPlusOneDay() {
        PaymentCalculationResult result = calc(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 2));
        assertThat(result.completedMonths()).isEqualTo(1);
        assertThat(result.cappedAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("1 month + 3 days yields 1 completed month")
    void oneMonthPlusThreeDays() {
        PaymentCalculationResult result = calc(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 18));
        assertThat(result.completedMonths()).isEqualTo(1);
    }

    @Test
    @DisplayName("6 weeks yield exactly 1 completed month")
    void sixWeeksYieldOneMonth() {
        PaymentCalculationResult result = calc(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 12));
        assertThat(result.completedMonths()).isEqualTo(1);
        assertThat(result.payableMonths()).isEqualTo(1);
        assertThat(result.cappedAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("2 months yield 100 TND")
    void twoMonths() {
        PaymentCalculationResult result = calc(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 3, 10));
        assertThat(result.completedMonths()).isEqualTo(2);
        assertThat(result.cappedAmount()).isEqualByComparingTo("100.00");
        assertThat(result.capApplied()).isFalse();
    }

    @Test
    @DisplayName("Exactly 3 months yield 150 TND without tripping the cap guard")
    void exactlyThreeMonths() {
        PaymentCalculationResult result = calc(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1));
        assertThat(result.completedMonths()).isEqualTo(3);
        assertThat(result.payableMonths()).isEqualTo(3);
        assertThat(result.calculatedAmount()).isEqualByComparingTo("150.00");
        assertThat(result.cappedAmount()).isEqualByComparingTo("150.00");
        assertThat(result.capApplied()).isFalse();
    }

    @Test
    @DisplayName("3 months + 1 day still yield 3 completed months")
    void threeMonthsPlusOneDay() {
        PaymentCalculationResult result = calc(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 2));
        assertThat(result.completedMonths()).isEqualTo(3);
        assertThat(result.payableMonths()).isEqualTo(3);
    }

    @Test
    @DisplayName("4 months are capped to 3 payable months (150 TND, real duration untouched)")
    void fourMonthsCapped() {
        PaymentCalculationResult result = calc(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 1));
        assertThat(result.completedMonths()).isEqualTo(4);
        assertThat(result.payableMonths()).isEqualTo(3);
        assertThat(result.cappedAmount()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("6-month PFE pays 150 TND (payable duration capped, actual duration preserved)")
    void sixMonthPfeCapped() {
        PaymentCalculationResult result = calc(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 8, 1));
        assertThat(result.completedMonths()).isEqualTo(6);
        assertThat(result.payableMonths()).isEqualTo(3);
        assertThat(result.cappedAmount()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("12 months never exceed 150 TND")
    void twelveMonthsCapped() {
        PaymentCalculationResult result = calc(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
        assertThat(result.completedMonths()).isEqualTo(12);
        assertThat(result.payableMonths()).isEqualTo(3);
        assertThat(result.cappedAmount()).isEqualByComparingTo("150.00");
        assertThat(result.capApplied()).isTrue();
    }

    @Test
    @DisplayName("Cap guard holds even if the configured rate is raised")
    void capHoldsUnderRaisedRate() {
        PaymentCalculationResult result = PaymentCalculationService.calculate(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1),
                new BigDecimal("200.00"), 3, MAX_150, "TND");
        assertThat(result.calculatedAmount()).isEqualByComparingTo("600.00");
        assertThat(result.cappedAmount()).isEqualByComparingTo("150.00");
        assertThat(result.capApplied()).isTrue();
    }

    @Test
    @DisplayName("Cap guard holds even if the payable-month cap is raised")
    void capHoldsUnderRaisedPayableCap() {
        PaymentCalculationResult result = PaymentCalculationService.calculate(
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                RATE_50, 12, MAX_150, "TND");
        assertThat(result.payableMonths()).isEqualTo(12);
        assertThat(result.calculatedAmount()).isEqualByComparingTo("600.00");
        assertThat(result.cappedAmount()).isEqualByComparingTo("150.00");
        assertThat(result.capApplied()).isTrue();
    }

    @Test
    @DisplayName("Inverted range yields 0 (never negative)")
    void invertedRangeYieldsZero() {
        PaymentCalculationResult result = calc(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 1, 1));
        assertThat(result.completedMonths()).isEqualTo(0);
        assertThat(result.cappedAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Month-end edge follows anniversary semantics (Jan 31 + 1 month = Feb 28)")
    void monthEndEdge() {
        // plusMonths anniversary lands exactly on Feb 28 → counts as completed.
        PaymentCalculationResult anniversary = calc(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28));
        assertThat(anniversary.completedMonths()).isEqualTo(1);
        // One day short of the anniversary → not completed.
        PaymentCalculationResult shortOf = calc(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 27));
        assertThat(shortOf.completedMonths()).isEqualTo(0);
    }

    @Test
    @DisplayName("Amounts always carry scale 2 and the configured currency")
    void scaleAndCurrency() {
        PaymentCalculationResult result = PaymentCalculationService.calculate(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
                new BigDecimal("50"), 3, new BigDecimal("150"), "TND");
        assertThat(result.ratePerMonth().scale()).isEqualTo(2);
        assertThat(result.calculatedAmount().scale()).isEqualTo(2);
        assertThat(result.cappedAmount().scale()).isEqualTo(2);
        assertThat(result.currencyCode()).isEqualTo("TND");
    }

    @Test
    @DisplayName("Null inputs and negative policy values are rejected")
    void invalidInputsRejected() {
        assertThatThrownBy(() -> PaymentCalculationService.calculate(
                null, LocalDate.now(), RATE_50, 3, MAX_150, "TND"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PaymentCalculationService.calculate(
                LocalDate.now(), LocalDate.now(), new BigDecimal("-1"), 3, MAX_150, "TND"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentCalculationService.calculate(
                LocalDate.now(), LocalDate.now(), RATE_50, -1, MAX_150, "TND"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
