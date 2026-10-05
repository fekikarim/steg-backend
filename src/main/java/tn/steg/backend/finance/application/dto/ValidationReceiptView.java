package tn.steg.backend.finance.application.dto;

import java.math.BigDecimal;

/** S7 validation receipt view: stable reference + financial-model amount. */
public record ValidationReceiptView(
        String reference,
        BigDecimal amount,
        String currency,
        String financeCaseId
) {
}
