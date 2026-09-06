package tn.steg.backend.workflow.domain.repository;

import tn.steg.backend.workflow.domain.model.ApplicationWorkflowInstance;
import tn.steg.backend.workflow.domain.model.InternshipWorkflowInstance;
import tn.steg.backend.workflow.domain.model.PaymentWorkflowInstance;
import tn.steg.backend.workflow.domain.model.WorkflowInstance;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowInstanceRepository {
    Optional<WorkflowInstance> findById(UUID id);
    Optional<ApplicationWorkflowInstance> findApplicationInstanceByApplicationId(UUID applicationId);
    Optional<InternshipWorkflowInstance> findInternshipInstanceByInternshipId(UUID internshipId);
    Optional<PaymentWorkflowInstance> findPaymentInstanceByFinanceCaseId(UUID financeCaseId);
    /**
     * A14 N+1 fix: bulk-load payment workflow instances for a whole finance-case
     * page in one query instead of one query per finance case.
     */
    java.util.List<PaymentWorkflowInstance> findPaymentInstancesByFinanceCaseIdIn(Collection<UUID> financeCaseIds);
    WorkflowInstance save(WorkflowInstance instance);
    WorkflowInstance saveAndFlush(WorkflowInstance instance);
}
