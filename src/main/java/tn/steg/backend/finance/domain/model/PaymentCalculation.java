package tn.steg.backend.finance.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_calculations")
public class PaymentCalculation extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "finance_case_id", nullable = false, unique = true)
    private FinanceCase financeCase;

    @Column(name = "completed_months", nullable = false)
    private Integer completedMonths;

    @Column(name = "payable_months", nullable = false)
    private Integer payableMonths;

    @Column(name = "rate_per_month", nullable = false, precision = 10, scale = 2)
    private BigDecimal ratePerMonth;

    @Column(name = "calculated_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal calculatedAmount;

    @Column(name = "capped_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal cappedAmount;

    @Column(name = "cap_applied", nullable = false)
    private Boolean capApplied = false;

    @Column(name = "currency_code", nullable = false, length = 10)
    private String currencyCode = "TND";

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    public PaymentCalculation(FinanceCase financeCase, Integer completedMonths, Integer payableMonths,
                              BigDecimal ratePerMonth, BigDecimal calculatedAmount, BigDecimal cappedAmount,
                              Boolean capApplied, String currencyCode) {
        this.financeCase = financeCase;
        this.completedMonths = completedMonths;
        this.payableMonths = payableMonths;
        this.ratePerMonth = ratePerMonth;
        this.calculatedAmount = calculatedAmount;
        this.cappedAmount = cappedAmount;
        this.capApplied = capApplied;
        this.currencyCode = currencyCode;
        this.calculatedAt = Instant.now();
    }
}
