package tn.steg.backend.workflow.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.InvalidStateTransitionException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.application.InternshipLifecycleService;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.internship.domain.service.InternshipClassificationResult;
import tn.steg.backend.internship.domain.service.InternshipClassificationService;
import tn.steg.backend.companion.domain.repository.InternshipJournalRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.repository.DepartmentRepository;
import tn.steg.backend.workflow.application.dto.ApplicationApprovalRequest;
import tn.steg.backend.workflow.application.dto.ApplicationApprovalResponse;
import tn.steg.backend.workflow.application.dto.WorkflowActionResponse;
import tn.steg.backend.workflow.domain.model.ApprovalDecision;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;

import java.time.Instant;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class ApplicationApprovalService {

    private final InternshipApplicationRepository applicationRepository;
    private final InternshipRepository internshipRepository;
    private final InternshipAssignmentRepository assignmentRepository;
    private final InternshipJournalRepository journalRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final WorkflowService workflowService;
    /** S6b: the internship status is written by this authority, never here. */
    private final InternshipLifecycleService internshipLifecycleService;
    private final AuditService auditService;
        private final MobileAccountProvisioningService mobileAccountProvisioningService;

    private final InternshipClassificationService classificationService = new InternshipClassificationService();

    @Transactional
    public ApplicationApprovalResponse approve(
            java.util.UUID applicationId,
            ApplicationApprovalRequest request,
            UserPrincipal actor) {
        InternshipApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + applicationId));

        // AGENTS.md §4 + Scenario A: the Admin approves a fresh application, so
        // SUBMITTED and RESUBMITTED are approvable; UNDER_REVIEW remains valid
        // for two-step reviews. Every other status is a 409.
        if (application.getStatus() != ApplicationStatus.SUBMITTED
                && application.getStatus() != ApplicationStatus.RESUBMITTED
                && application.getStatus() != ApplicationStatus.UNDER_REVIEW) {
            throw new InvalidStateTransitionException(
                    application.getStatus().name(), "APPROVED",
                    "Only a submitted, resubmitted or under-review application can be approved. Current status: "
                            + application.getStatus());
        }

        User supervisor = request.supervisorUserId() == null
                ? userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Approving Admin not found: " + actor.getId()))
                : userRepository.findById(request.supervisorUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Supervisor not found: " + request.supervisorUserId()));

        Department department = departmentRepository.findById(request.departmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + request.departmentId()));

        if (supervisor.getAssignedRoles().stream().noneMatch(role ->
                "ADMIN".equalsIgnoreCase(role.getCode()) || "SUPERVISOR".equalsIgnoreCase(role.getCode()))) {
            throw new BusinessRuleException("INVALID_SUPERVISOR", "Selected user is not an Admin or Supervisor.");
        }

        WorkflowActionResponse workflowAction = workflowService.transitionApplication(
                applicationId,
                new tn.steg.backend.workflow.application.dto.WorkflowTransitionRequest(
                        "FINAL_DECISION", WorkflowActionType.APPROVAL, ApprovalDecision.APPROVED, null),
                actor);

        LocalDate start = application.getDesiredStartDate() != null ? application.getDesiredStartDate() : LocalDate.now();
        LocalDate end = application.getDesiredEndDate() != null ? application.getDesiredEndDate() : start.plusMonths(1);
        InternshipClassificationResult classification = classificationService.classify(start, end, null, null);

        Internship internship = new Internship(
                "INT-" + application.getReference(),
                application.getCandidate(),
                start,
                end,
                classification.type(),
                classification.requirement());
        internship.setApplication(application);
        internship.setPlannedAt(Instant.now());
        // S6b: the internship status is written by InternshipLifecycleService only.
        internshipLifecycleService.initializeStatus(internship);
        internship.setSupervisorUser(supervisor);
        internship = internshipRepository.save(internship);
        journalRepository.save(new tn.steg.backend.companion.domain.model.InternshipJournal(internship));

        InternshipAssignment assignment = new InternshipAssignment(
                internship, department, null, null, LocalDate.now(), start, end, AssignmentStatus.ACTIVE);
        assignment.setSupervisorUser(supervisor);
        assignment.setAssignedByUser(userRepository.findById(actor.getId()).orElse(null));
        assignmentRepository.save(assignment);

        MobileAccountProvisioningService.ProvisioningResult provisioning =
                mobileAccountProvisioningService.provisionIfRequired(internship).orElse(null);

        auditService.log("APPLICATION_APPROVED_WITH_SUPERVISOR", "InternshipApplication", applicationId,
                null,
                java.util.Map.of("supervisorUserId", supervisor.getId(), "internshipId", internship.getId()),
                actor.getId(), null);

        return new ApplicationApprovalResponse(
                workflowAction,
                InternshipResponse.from(internship),
                supervisor.getId(),
                provisioning != null ? provisioning.email() : null,
                provisioning != null ? provisioning.temporaryPassword() : null,
                provisioning != null && provisioning.emailSent());
    }
}