package tn.steg.backend.workflow.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.workflow.domain.model.ApplicationWorkflowInstance;
import tn.steg.backend.workflow.domain.model.InternshipWorkflowInstance;
import tn.steg.backend.workflow.domain.model.PaymentWorkflowInstance;
import tn.steg.backend.workflow.domain.model.WorkflowInstance;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for WorkflowInstance and its subtypes.
 */
@Repository
public interface WorkflowInstanceJpaRepository extends JpaRepository<WorkflowInstance, UUID> {

    @Query("SELECT wi FROM ApplicationWorkflowInstance wi WHERE wi.application.id = :applicationId")
    Optional<ApplicationWorkflowInstance> findApplicationInstanceByApplicationId(@Param("applicationId") UUID applicationId);

    @Query("SELECT wi FROM InternshipWorkflowInstance wi WHERE wi.internship.id = :internshipId")
    Optional<InternshipWorkflowInstance> findInternshipInstanceByInternshipId(@Param("internshipId") UUID internshipId);

    @Query("SELECT wi FROM PaymentWorkflowInstance wi WHERE wi.financeCase.id = :financeCaseId")
    Optional<PaymentWorkflowInstance> findPaymentInstanceByFinanceCaseId(@Param("financeCaseId") UUID financeCaseId);

    @Query("SELECT wi FROM PaymentWorkflowInstance wi WHERE wi.financeCase.id IN :financeCaseIds")
    List<PaymentWorkflowInstance> findPaymentInstancesByFinanceCaseIdIn(@Param("financeCaseIds") Collection<UUID> financeCaseIds);
}
