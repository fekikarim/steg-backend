package tn.steg.backend.reporting.domain.model;

import java.math.BigDecimal;

/**
 * Read-model projection for payment totals aggregated by period
 * (calendar year/month) and, when not filtered to a single department,
 * broken down per destination department.
 */
public interface PaymentTotalRow {

    int getYear();

    int getMonth();

    String getDepartmentCode();

    BigDecimal getTotalAmount();

    long getReceiptCount();
}