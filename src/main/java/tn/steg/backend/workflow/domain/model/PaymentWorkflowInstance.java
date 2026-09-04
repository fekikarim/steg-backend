package tn.steg.backend.workflow.domain.model;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.finance.domain.model.FinanceCase;

@Getter
@Setter
@NoArgsConstructor
@Entity
@DiscriminatorValue("PAYMENT")
public class PaymentWorkflowInstance extends WorkflowInstance {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finance_case_id")
    private FinanceCase financeCase;

    public PaymentWorkflowInstance(WorkflowDefinition definition, FinanceCase financeCase) {
        super(definition);
        this.financeCase = financeCase;
    }
}
