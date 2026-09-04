package tn.steg.backend.workflow.domain.repository;

import tn.steg.backend.workflow.domain.model.ApplicationWorkflowInstance;
import tn.steg.backend.workflow.domain.model.InternshipWorkflowInstance;
import tn.steg.backend.workflow.domain.model.PaymentWorkflowInstance;
import tn.steg.backend.workflow.domain.model.WorkflowInstance;

import java.util.Optional;
import java.util.UUID;

public interface WorkflowInstanceRepository {
    Optional<WorkflowInstance> findById(UUID id);
    Optional<ApplicationWorkflowInstance> findApplicationInstanceByApplicationId(UUID applicationId);
    Optional<InternshipWorkflowInstance> findInternshipInstanceByInternshipId(UUID internshipId);
    Optional<PaymentWorkflowInstance> findPaymentInstanceByFinanceCaseId(UUID financeCaseId);
    WorkflowInstance save(WorkflowInstance instance);
    WorkflowInstance saveAndFlush(WorkflowInstance instance);
}
