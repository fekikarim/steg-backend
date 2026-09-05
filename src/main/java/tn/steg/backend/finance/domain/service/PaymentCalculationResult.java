package tn.steg.backend.finance.domain.service;

import java.math.BigDecimal;

/**
 * Immutable result of the deterministic payment calculation (Phase A11).
 *
 * @param completedMonths number of FULLY completed calendar months in the internship period
 * @param payableMonths   billable months after the payable-duration cap
 * @param ratePerMonth    configured rate (default 50 TND, never hard-coded at call sites)
 * @param calculatedAmount payableMonths × ratePerMonth, scale 2
 * @param cappedAmount    min(calculatedAmount, maximum), scale 2
 * @param capApplied      true when either cap reduced the payout: the payable-duration
 *                        cap (payableMonths &lt; completedMonths) or the absolute amount cap
 * @param currencyCode    ISO currency code (default TND)
 */
public record PaymentCalculationResult(
        int completedMonths,
        int payableMonths,
        BigDecimal ratePerMonth,
        BigDecimal calculatedAmount,
        BigDecimal cappedAmount,
        boolean capApplied,
        String currencyCode
) {
}
