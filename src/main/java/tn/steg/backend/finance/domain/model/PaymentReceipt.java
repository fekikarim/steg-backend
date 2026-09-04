package tn.steg.backend.finance.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.organization.domain.model.Employee;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_receipts")
public class PaymentReceipt extends BaseEntity {

    @Column(name = "reference", nullable = false, unique = true, length = 50)
    private String reference;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "finance_case_id", nullable = false, unique = true)
    private FinanceCase financeCase;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_by_id")
    private Employee issuedBy;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_asset_id", nullable = false)
    private FileAsset pdfFile;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private PaymentReceiptStatus status = PaymentReceiptStatus.GENERATED;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_months", nullable = false)
    private Integer paidMonths;

    @Column(name = "currency_code", nullable = false, length = 10)
    private String currencyCode = "TND";

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "issued_at")
    private Instant issuedAt;

    public PaymentReceipt(String reference, FinanceCase financeCase, FileAsset pdfFile,
                          BigDecimal amount, Integer paidMonths, String currencyCode) {
        this.reference = reference;
        this.financeCase = financeCase;
        this.pdfFile = pdfFile;
        this.amount = amount;
        this.paidMonths = paidMonths;
        this.currencyCode = currencyCode;
    }
}
