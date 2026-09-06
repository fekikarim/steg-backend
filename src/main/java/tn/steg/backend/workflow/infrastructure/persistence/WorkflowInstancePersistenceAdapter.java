package tn.steg.backend.workflow.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tn.steg.backend.workflow.domain.model.ApplicationWorkflowInstance;
import tn.steg.backend.workflow.domain.model.InternshipWorkflowInstance;
import tn.steg.backend.workflow.domain.model.PaymentWorkflowInstance;
import tn.steg.backend.workflow.domain.model.WorkflowInstance;
import tn.steg.backend.workflow.domain.repository.WorkflowInstanceRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter bridging the domain WorkflowInstanceRepository port
 * to the Spring Data JPA implementation.
 */
@Component
@RequiredArgsConstructor
public class WorkflowInstancePersistenceAdapter implements WorkflowInstanceRepository {

    private final WorkflowInstanceJpaRepository jpaRepository;

    @Override
    public Optional<WorkflowInstance> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<ApplicationWorkflowInstance> findApplicationInstanceByApplicationId(UUID applicationId) {
        return jpaRepository.findApplicationInstanceByApplicationId(applicationId);
    }

    @Override
    public Optional<InternshipWorkflowInstance> findInternshipInstanceByInternshipId(UUID internshipId) {
        return jpaRepository.findInternshipInstanceByInternshipId(internshipId);
    }

    @Override
    public Optional<PaymentWorkflowInstance> findPaymentInstanceByFinanceCaseId(UUID financeCaseId) {
        return jpaRepository.findPaymentInstanceByFinanceCaseId(financeCaseId);
    }

    @Override
    public List<PaymentWorkflowInstance> findPaymentInstancesByFinanceCaseIdIn(Collection<UUID> financeCaseIds) {
        if (financeCaseIds == null || financeCaseIds.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findPaymentInstancesByFinanceCaseIdIn(financeCaseIds);
    }

    @Override
    public WorkflowInstance save(WorkflowInstance instance) {
        return jpaRepository.save(instance);
    }

    @Override
    public WorkflowInstance saveAndFlush(WorkflowInstance instance) {
        return jpaRepository.saveAndFlush(instance);
    }
}
