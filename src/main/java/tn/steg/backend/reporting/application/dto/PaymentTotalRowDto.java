package tn.steg.backend.reporting.application.dto;

import tn.steg.backend.reporting.domain.model.PaymentTotalRow;

import java.math.BigDecimal;

/**
 * Read-only payment rollup: total paid amount (and receipt count) for a
 * calendar (year, month) and destination department. When the report is
 * filtered to one department, {@code departmentCode} always equals it;
 * otherwise a {@code departmentCode} of {@code null} covers receipts whose
 * internship has no (current) assignment.
 */
public record PaymentTotalRowDto(int year, int month, String departmentCode,
                                 BigDecimal totalAmount, long receiptCount) {

    public static PaymentTotalRowDto from(PaymentTotalRow row) {
        return new PaymentTotalRowDto(row.getYear(), row.getMonth(), row.getDepartmentCode(),
                row.getTotalAmount(), row.getReceiptCount());
    }
}