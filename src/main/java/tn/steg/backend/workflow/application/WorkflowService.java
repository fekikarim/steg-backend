package tn.steg.backend.workflow.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.common.domain.event.ApplicationAcceptedEvent;
import tn.steg.backend.common.domain.event.ApplicationRejectedEvent;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.workflow.application.dto.WorkflowActionResponse;
import tn.steg.backend.workflow.application.dto.WorkflowInstanceResponse;
import tn.steg.backend.workflow.application.dto.WorkflowTransitionRequest;
import tn.steg.backend.workflow.domain.guard.WorkflowTransitionGuard;
import tn.steg.backend.workflow.domain.model.*;
import tn.steg.backend.workflow.domain.repository.WorkflowActionRepository;
import tn.steg.backend.workflow.domain.repository.WorkflowDefinitionRepository;
import tn.steg.backend.workflow.domain.repository.WorkflowInstanceRepository;
import tn.steg.backend.workflow.domain.repository.WorkflowStepDefinitionRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Central workflow orchestrator for the Phase A5 engine.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Spawning WorkflowInstances for applications, internships, and payment cases.</li>
 *   <li>Validating transitions via the appropriate {@link WorkflowTransitionGuard}.</li>
 *   <li>Recording every transition as a {@link WorkflowAction} (audit trail).</li>
 *   <li>Propagating resulting state changes back to the business aggregate.</li>
 * </ul>
 *
 * <p>All mutating methods are wrapped in a single transaction to guarantee
 * that the WorkflowAction and the aggregate status change are atomic.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final String DEF_CODE_APPLICATION = "application-review";
    private static final String DEF_CODE_INTERNSHIP  = "internship-lifecycle";
    private static final String DEF_CODE_PAYMENT     = "payment-approval";

    private final WorkflowDefinitionRepository       definitionRepository;
    private final WorkflowStepDefinitionRepository   stepRepository;
    private final WorkflowInstanceRepository         instanceRepository;
    private final WorkflowActionRepository           actionRepository;
    private final UserRepository                     userRepository;

    /** Business facts for cross-cutting concerns (notifications); the workflow
     * module never depends on those consumers (Phase A10). */
    private final ApplicationEventPublisher          eventPublisher;

    /** All registered guards — Spring injects every bean implementing this interface. */
    @SuppressWarnings("rawtypes")
    private final List<WorkflowTransitionGuard> guards;

    // =========================================================================
    // Instance spawning
    // =========================================================================

    /**
     * Creates and persists a new ApplicationWorkflowInstance for the given application.
     * Sets the initial step to SUBMITTED and marks the instance as RUNNING.
     */
    @Transactional
    public ApplicationWorkflowInstance spawnApplicationWorkflow(InternshipApplication application) {
        WorkflowDefinition def = requireDefinition(DEF_CODE_APPLICATION);
        WorkflowStepDefinition initialStep = requireStep(def.getId(), "SUBMITTED");

        ApplicationWorkflowInstance instance = new ApplicationWorkflowInstance(def, application);
        instance.setCurrentStep(initialStep);
        instance.setStatus(WorkflowStatus.RUNNING);

        instance = (ApplicationWorkflowInstance) instanceRepository.save(instance);
        log.info("Spawned ApplicationWorkflowInstance id={} for application ref={}",
                instance.getId(), application.getReference());
        return instance;
    }

    /**
     * Creates and persists a new InternshipWorkflowInstance for the given internship.
     * Sets the initial step to PLANNED and marks the instance as RUNNING.
     */
    @Transactional
    public InternshipWorkflowInstance spawnInternshipWorkflow(Internship internship) {
        WorkflowDefinition def = requireDefinition(DEF_CODE_INTERNSHIP);
        WorkflowStepDefinition initialStep = requireStep(def.getId(), "PLANNED");

        InternshipWorkflowInstance instance = new InternshipWorkflowInstance(def, internship);
        instance.setCurrentStep(initialStep);
        instance.setStatus(WorkflowStatus.RUNNING);

        instance = (InternshipWorkflowInstance) instanceRepository.save(instance);
        log.info("Spawned InternshipWorkflowInstance id={} for internship ref={}",
                instance.getId(), internship.getReference());
        return instance;
    }

    // =========================================================================
    // Transition execution
    // =========================================================================

    /**
     * Executes a workflow transition on an ApplicationWorkflowInstance.
     * <p>
     * Steps:
     * <ol>
     *   <li>Load the workflow instance linked to the given application ID.</li>
     *   <li>Find the target WorkflowStepDefinition by code.</li>
     *   <li>Validate the transition via {@link WorkflowTransitionGuard}.</li>
     *   <li>Advance the aggregate status to match the workflow step.</li>
     *   <li>Persist a {@link WorkflowAction} record (audit trail).</li>
     *   <li>Update instance.currentStep and close instance if terminal step reached.</li>
     * </ol>
     *
     * @param applicationId the UUID of the InternshipApplication
     * @param request       transition parameters (target step, action type, decision, comment)
     * @param actor         the authenticated user performing the action
     * @return the recorded WorkflowAction as a DTO
     */
    @Transactional
    public WorkflowActionResponse transitionApplication(UUID applicationId,
                                                        WorkflowTransitionRequest request,
                                                        UserPrincipal actor) {
        ApplicationWorkflowInstance instance = instanceRepository
                .findApplicationInstanceByApplicationId(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active workflow found for application: " + applicationId));

        WorkflowStepDefinition targetStep = requireStep(instance.getDefinition().getId(), request.targetStepCode());
        User performer = requireUser(actor.getId());

        // Guard validation (throws BusinessRuleException on illegal transitions)
        resolveGuard(ApplicationWorkflowInstance.class).validateTransition(instance, request.targetStepCode(),
                request.actionType(), request.decision());

        // Apply aggregate state change
        applyApplicationStatusTransition(instance.getApplication(), request.targetStepCode(), request.decision(), request.comment());

        // Advance workflow state
        instance.setCurrentStep(targetStep);
        if (isTerminalApplicationStep(request.targetStepCode(), request.decision())) {
            instance.setStatus(WorkflowStatus.COMPLETED);
            instance.setCompletedAt(Instant.now());
        }
        instanceRepository.save(instance);

        // Record action
        WorkflowAction action = persistAction(instance, targetStep, performer, request);
        log.info("ApplicationWorkflow transition: appId={} → step={} decision={} by actor={}",
                applicationId, request.targetStepCode(), request.decision(), actor.getId());

        // Phase A10: notify the candidate on final accept/reject (consumed
        // AFTER_COMMIT, so no alert fires if this transaction rolls back).
        if ("FINAL_DECISION".equalsIgnoreCase(request.targetStepCode())) {
            InternshipApplication decided = instance.getApplication();
            UUID candidateUserId = decided.getCandidate().getUser().getId();
            if (request.decision() == ApprovalDecision.APPROVED) {
                eventPublisher.publishEvent(new ApplicationAcceptedEvent(
                        decided.getId(), decided.getReference(), candidateUserId, actor.getId()));
            } else if (request.decision() == ApprovalDecision.REJECTED) {
                eventPublisher.publishEvent(new ApplicationRejectedEvent(
                        decided.getId(), decided.getReference(), candidateUserId, actor.getId(), request.comment()));
            }
        }
        return WorkflowActionResponse.from(action);
    }

    /**
     * Executes a workflow transition on an InternshipWorkflowInstance.
     *
     * @param internshipId the UUID of the Internship
     * @param request      transition parameters
     * @param actor        the authenticated user performing the action
     * @return the recorded WorkflowAction as a DTO
     */
    @Transactional
    public WorkflowActionResponse transitionInternship(UUID internshipId,
                                                       WorkflowTransitionRequest request,
                                                       UserPrincipal actor) {
        InternshipWorkflowInstance instance = instanceRepository
                .findInternshipInstanceByInternshipId(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active workflow found for internship: " + internshipId));

        WorkflowStepDefinition targetStep = requireStep(instance.getDefinition().getId(), request.targetStepCode());
        User performer = requireUser(actor.getId());

        resolveGuard(InternshipWorkflowInstance.class).validateTransition(instance, request.targetStepCode(),
                request.actionType(), request.decision());

        // Apply aggregate state change
        applyInternshipStatusTransition(instance.getInternship(), request.targetStepCode());

        // Advance workflow state
        instance.setCurrentStep(targetStep);
        if ("COMPLETED".equalsIgnoreCase(request.targetStepCode())) {
            instance.setStatus(WorkflowStatus.COMPLETED);
            instance.setCompletedAt(Instant.now());
        }
        instanceRepository.save(instance);

        WorkflowAction action = persistAction(instance, targetStep, performer, request);
        log.info("InternshipWorkflow transition: internshipId={} → step={} by actor={}",
                internshipId, request.targetStepCode(), actor.getId());
        return WorkflowActionResponse.from(action);
    }

    // =========================================================================
    // Query methods
    // =========================================================================

    /**
     * Returns the current workflow state for an application.
     */
    @Transactional(readOnly = true)
    public WorkflowInstanceResponse getApplicationWorkflowState(UUID applicationId) {
        ApplicationWorkflowInstance instance = instanceRepository
                .findApplicationInstanceByApplicationId(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No workflow found for application: " + applicationId));
        return WorkflowInstanceResponse.from(instance);
    }

    /**
     * Returns the current workflow state for an internship.
     */
    @Transactional(readOnly = true)
    public WorkflowInstanceResponse getInternshipWorkflowState(UUID internshipId) {
        InternshipWorkflowInstance instance = instanceRepository
                .findInternshipInstanceByInternshipId(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No workflow found for internship: " + internshipId));
        return WorkflowInstanceResponse.from(instance);
    }

    /**
     * Lists all recorded actions for a given workflow instance (audit trail).
     */
    @Transactional(readOnly = true)
    public List<WorkflowActionResponse> listActions(UUID instanceId) {
        return actionRepository.findByInstanceIdOrderBySequenceNumberAsc(instanceId)
                .stream()
                .map(WorkflowActionResponse::from)
                .toList();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private WorkflowDefinition requireDefinition(String code) {
        return definitionRepository.findByCode(code)
                .orElseThrow(() -> new BusinessRuleException("WORKFLOW_DEFINITION_NOT_FOUND",
                        "Workflow definition not found for code: " + code));
    }

    private WorkflowStepDefinition requireStep(UUID definitionId, String code) {
        return stepRepository.findByDefinitionIdAndCode(definitionId, code)
                .orElseThrow(() -> new BusinessRuleException("WORKFLOW_STEP_NOT_FOUND",
                        "Workflow step not found: " + code + " in definition " + definitionId));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends WorkflowInstance> WorkflowTransitionGuard<T> resolveGuard(Class<T> instanceType) {
        return (WorkflowTransitionGuard<T>) guards.stream()
                .filter(g -> {
                    // instantiate a dummy to test supports() won't work — match by generic type
                    // We rely on the guard's supports() test against the actual class.
                    // Since we're dispatching by class name here, match guard by name convention.
                    String guardClassName = g.getClass().getSimpleName();
                    String instanceSimpleName = instanceType.getSimpleName(); // e.g. "ApplicationWorkflowInstance"
                    return guardClassName.contains(instanceSimpleName.replace("WorkflowInstance", ""));
                })
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("WORKFLOW_GUARD_NOT_FOUND",
                        "No guard registered for type: " + instanceType.getSimpleName()));
    }

    private WorkflowAction persistAction(WorkflowInstance instance, WorkflowStepDefinition step,
                                         User performer, WorkflowTransitionRequest request) {
        long nextSeq = actionRepository.countByInstanceId(instance.getId()) + 1;
        WorkflowAction action = new WorkflowAction(
                instance, step, performer,
                request.actionType(), request.decision(), request.comment(), nextSeq);
        return actionRepository.save(action);
    }

    /**
     * Applies the business aggregate status change for an application transition.
     * This is the ONLY place that may directly update InternshipApplication.status from Phase A5+.
     */
    private void applyApplicationStatusTransition(InternshipApplication application,
                                                   String targetStepCode,
                                                   ApprovalDecision decision,
                                                   String comment) {
        switch (targetStepCode.toUpperCase()) {
            case "UNDER_REVIEW" -> application.setStatus(ApplicationStatus.UNDER_REVIEW);
            case "FINAL_DECISION" -> {
                if (decision == ApprovalDecision.APPROVED) {
                    application.setStatus(ApplicationStatus.ACCEPTED);
                } else if (decision == ApprovalDecision.REJECTED) {
                    application.setStatus(ApplicationStatus.REJECTED);
                    if (comment != null && !comment.isBlank()) {
                        application.setRejectionReason(comment);
                    }
                } else if (decision == ApprovalDecision.NEEDS_CORRECTION) {
                    application.setStatus(ApplicationStatus.NEEDS_CORRECTION);
                    if (comment != null && !comment.isBlank()) {
                        application.setCorrectionComment(comment);
                    }
                } else {
                    throw new BusinessRuleException("INVALID_DECISION",
                            "A decision (APPROVED/REJECTED/NEEDS_CORRECTION) is required for FINAL_DECISION step.");
                }
            }
            default -> throw new BusinessRuleException("UNKNOWN_APPLICATION_STEP",
                    "Unknown application workflow step: " + targetStepCode);
        }
    }

    /**
     * Applies the business aggregate status change for an internship transition.
     */
    private void applyInternshipStatusTransition(Internship internship, String targetStepCode) {
        switch (targetStepCode.toUpperCase()) {
            case "ACTIVE"    -> internship.setStatus(InternshipStatus.ACTIVE);
            case "COMPLETED" -> internship.setStatus(InternshipStatus.COMPLETED);
            default -> throw new BusinessRuleException("UNKNOWN_INTERNSHIP_STEP",
                    "Unknown internship workflow step: " + targetStepCode);
        }
    }

    /**
     * A FINAL_DECISION step terminates the application workflow instance.
     */
    private boolean isTerminalApplicationStep(String stepCode, ApprovalDecision decision) {
        return "FINAL_DECISION".equalsIgnoreCase(stepCode) && decision != null;
    }
}
