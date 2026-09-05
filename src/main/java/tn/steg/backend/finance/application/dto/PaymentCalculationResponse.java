package tn.steg.backend.finance.application.dto;

import tn.steg.backend.finance.domain.model.PaymentCalculation;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentCalculationResponse(
        int completedMonths,
        int payableMonths,
        BigDecimal ratePerMonth,
        BigDecimal calculatedAmount,
        BigDecimal cappedAmount,
        boolean capApplied,
        String currencyCode,
        Instant calculatedAt
) {
    public static PaymentCalculationResponse from(PaymentCalculation calculation) {
        return new PaymentCalculationResponse(
                calculation.getCompletedMonths(),
                calculation.getPayableMonths(),
                calculation.getRatePerMonth(),
                calculation.getCalculatedAmount(),
                calculation.getCappedAmount(),
                Boolean.TRUE.equals(calculation.getCapApplied()),
                calculation.getCurrencyCode(),
                calculation.getCalculatedAt());
    }
}
