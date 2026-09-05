package tn.steg.backend.finance.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Deterministic payment calculation (Phase A11). Pure function: no Spring, no
 * I/O, no clock — exhaustively unit-tested in isolation. Clients (UI, AI) must
 * never compute the authoritative amount; the backend always recomputes it.
 *
 * <p>Completed-month rule: a calendar month counts only when FULLY completed,
 * measured by month anniversaries from the start date. {@code n} counts iff
 * {@code startDate.plusMonths(n) <= endDate}. In particular:
 * <ul>
 *   <li>29 days (e.g. Jan 1 → Jan 30) → 0 completed months (no prorating, ever);</li>
 *   <li>1 month + 3 days (Jan 1 → Feb 4) → 1;</li>
 *   <li>6 weeks (Jan 1 → Feb 12) → 1;</li>
 *   <li>exactly 3 months (Jan 1 → Apr 1) → 3 (end-inclusive anniversary counts).</li>
 * </ul>
 * Day-count division ({@code days / 30}) is explicitly forbidden as the basis.
 */
public final class PaymentCalculationService {

    private PaymentCalculationService() {
    }

    /**
     * Calculates the full payment breakdown.
     *
     * @param startDate       internship start (inclusive)
     * @param endDate         internship end (inclusive)
     * @param ratePerMonth    configured monthly rate (requirements default 50 TND)
     * @param maxPayableMonths payable-duration cap (requirements default 3)
     * @param maxAmount       absolute payment cap (requirements default 150 TND)
     * @param currencyCode    ISO currency code (requirements default TND)
     * @return immutable breakdown; never null
     */
    public static PaymentCalculationResult calculate(LocalDate startDate, LocalDate endDate,
                                                     BigDecimal ratePerMonth, int maxPayableMonths,
                                                     BigDecimal maxAmount, String currencyCode) {
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        Objects.requireNonNull(ratePerMonth, "ratePerMonth must not be null");
        Objects.requireNonNull(maxAmount, "maxAmount must not be null");
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        if (maxPayableMonths < 0) {
            throw new IllegalArgumentException("maxPayableMonths must not be negative");
        }
        if (ratePerMonth.signum() < 0 || maxAmount.signum() < 0) {
            throw new IllegalArgumentException("ratePerMonth and maxAmount must not be negative");
        }

        int completedMonths = completedMonths(startDate, endDate);
        int payableMonths = Math.min(completedMonths, maxPayableMonths);
        BigDecimal calculatedAmount = ratePerMonth
                .multiply(BigDecimal.valueOf(payableMonths))
                .setScale(2, RoundingMode.HALF_UP);
        // Either cap counts: the payable-duration cap (e.g. a 6-month PFE paid
        // for 3) or the defensive absolute amount cap (e.g. raised rate config).
        boolean capApplied = payableMonths < completedMonths || calculatedAmount.compareTo(maxAmount) > 0;
        BigDecimal cappedAmount = capApplied
                ? maxAmount.setScale(2, RoundingMode.HALF_UP)
                : calculatedAmount;

        return new PaymentCalculationResult(
                completedMonths,
                payableMonths,
                ratePerMonth.setScale(2, RoundingMode.HALF_UP),
                calculatedAmount,
                cappedAmount,
                capApplied,
                currencyCode);
    }

    /**
     * Counts fully completed calendar months in {@code [startDate, endDate]}
     * by month anniversaries. An inverted or empty range yields 0 (never negative).
     */
    static int completedMonths(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            return 0;
        }
        int completed = 0;
        while (!startDate.plusMonths(completed + 1).isAfter(endDate)) {
            completed++;
        }
        return completed;
    }
}
