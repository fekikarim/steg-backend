package tn.steg.backend.finance.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;
import tn.steg.backend.organization.domain.model.Employee;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_approvals")
public class PaymentApproval extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "finance_case_id", nullable = false)
    private FinanceCase financeCase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decided_by_id", nullable = false)
    private Employee decidedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 50)
    private PaymentApprovalDecision decision = PaymentApprovalDecision.PENDING;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "decision_sequence", nullable = false)
    private Integer decisionSequence = 1;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    public PaymentApproval(FinanceCase financeCase, Employee decidedBy, PaymentApprovalDecision decision,
                           String comment, Integer decisionSequence) {
        this.financeCase = financeCase;
        this.decidedBy = decidedBy;
        this.decision = decision;
        this.comment = comment;
        this.decisionSequence = decisionSequence;
        this.decidedAt = Instant.now();
    }
}
