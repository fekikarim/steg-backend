package tn.steg.backend.application.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.application.application.dto.ApplicationCreateRequest;
import tn.steg.backend.application.application.dto.ApplicationResponse;
import tn.steg.backend.application.application.dto.ApplicationUpdateRequest;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.organization.domain.repository.EmployeeRepository;
import tn.steg.backend.workflow.application.WorkflowService;
import tn.steg.backend.audit.application.AuditService;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.UUID;
import java.util.Map;

/**
 * Application service for the InternshipApplication lifecycle.
 * <p>
 * State machine (server-side enforcement):
 * <pre>
 *   DRAFT ──→ SUBMITTED (candidate; all mandatory docs present)
 *   SUBMITTED ──→ UNDER_REVIEW (ADMIN/HR)
 *   UNDER_REVIEW ──→ ACCEPTED | REJECTED | NEEDS_CORRECTION (ADMIN/HR)
 *   NEEDS_CORRECTION ──→ SUBMITTED (candidate resubmits)
 *   ANY ──→ WITHDRAWN (own candidate only, before ACCEPTED)
 * </pre>
 *
 * IDOR protection: every candidate-facing operation verifies the resource belongs to
 * the calling user before performing the action.
 */
@Slf4j
@Service
public class ApplicationService {

    private final InternshipApplicationRepository applicationRepository;
    private final CandidateRepository candidateRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;

    /** Lazy to avoid circular dependency: WorkflowService → UserRepository → ApplicationService chain. */
    @Lazy
    private final WorkflowService workflowService;

    public ApplicationService(InternshipApplicationRepository applicationRepository,
                               CandidateRepository candidateRepository,
                               EmployeeRepository employeeRepository,
                               AuditService auditService,
                               @Lazy WorkflowService workflowService) {
        this.applicationRepository = applicationRepository;
        this.candidateRepository   = candidateRepository;
        this.employeeRepository    = employeeRepository;
        this.auditService          = auditService;
        this.workflowService       = workflowService;
    }

    // -------------------------------------------------------------------------
    // Candidate-facing operations
    // -------------------------------------------------------------------------

    /**
     * Creates a new application in DRAFT status for the authenticated candidate.
     */
    @Transactional
    public ApplicationResponse createApplication(ApplicationCreateRequest request, UserPrincipal actor) {
        Candidate candidate = findCandidateByUserOrThrow(actor.getId());
        String reference = generateReference();

        InternshipApplication application = new InternshipApplication(
                reference, candidate, ApplicationStatus.DRAFT);
        application.setDesiredStartDate(request.desiredStartDate());
        application.setDesiredEndDate(request.desiredEndDate());
        application.setProposedTheme(request.proposedTheme());
        application.setSubmittedOnline(request.submittedOnline() != null ? request.submittedOnline() : true);

        application = applicationRepository.save(application);
        log.info("Application created: ref={} by candidate={}", reference, candidate.getId());
        auditService.log("APPLICATION_CREATED", "InternshipApplication", application.getId(), null,
                Map.of("reference", reference, "status", ApplicationStatus.DRAFT.toString()), actor.getId(), null);
        return ApplicationResponse.from(application);
    }

    /**
     * Lists applications visible to the caller:
     * - CANDIDATE sees only their own applications.
     * - ADMIN / HR see all applications.
     */
    @Transactional(readOnly = true)
    public List<ApplicationResponse> listApplications(UserPrincipal actor) {
        if (actor.hasRole("ADMIN") || actor.hasRole("HR")) {
            return applicationRepository.findAll().stream()
                    .map(ApplicationResponse::from)
                    .toList();
        }

        // CANDIDATE — find their candidate record then filter
        return candidateRepository.findAll().stream()
                .filter(c -> c.getUser() != null && c.getUser().getId().equals(actor.getId()))
                .findFirst()
                .map(candidate -> applicationRepository.findByCandidateId(candidate.getId()).stream()
                        .map(ApplicationResponse::from)
                        .toList())
                .orElse(List.of());
    }

    /**
     * Returns application detail. IDOR check: candidate can only read their own.
     */
    @Transactional(readOnly = true)
    public ApplicationResponse getApplication(UUID id, UserPrincipal actor) {
        InternshipApplication application = resolveWithIdorCheck(id, actor);
        return ApplicationResponse.from(application);
    }

    /**
     * Updates mutable fields of a DRAFT application. Only the owning candidate may update.
     */
    @Transactional
    public ApplicationResponse updateApplication(UUID id, ApplicationUpdateRequest request, UserPrincipal actor) {
        InternshipApplication application = resolveOwnCandidateApplicationOrThrow(id, actor);

        if (application.getStatus() != ApplicationStatus.DRAFT) {
            throw new BusinessRuleException("APPLICATION_NOT_DRAFT",
                    "Only DRAFT applications can be edited. Current status: " + application.getStatus());
        }

        application.setDesiredStartDate(request.desiredStartDate());
        application.setDesiredEndDate(request.desiredEndDate());
        application.setProposedTheme(request.proposedTheme());
        if (request.submittedOnline() != null) {
            application.setSubmittedOnline(request.submittedOnline());
        }

        application = applicationRepository.save(application);
        log.info("Application updated: id={}", id);
        auditService.log("APPLICATION_UPDATED", "InternshipApplication", id, null,
                Map.of("desiredStartDate", application.getDesiredStartDate(),
                        "desiredEndDate", application.getDesiredEndDate(),
                        "proposedTheme", application.getProposedTheme()), actor.getId(), null);
        return ApplicationResponse.from(application);
    }

    /**
     * Submits a DRAFT application (DRAFT → SUBMITTED).
     * Validates that all mandatory documents are attached before allowing submission.
     * (Document check is a no-op for now; Phase A6 will enforce it via ApplicationDocument records.)
     * <p>
     * Phase A5: also spawns an ApplicationWorkflowInstance so that all subsequent
     * staff-driven transitions flow through the Workflow Engine.
     */
    @Transactional
    public ApplicationResponse submitApplication(UUID id, UserPrincipal actor) {
        InternshipApplication application = resolveOwnCandidateApplicationOrThrow(id, actor);

        validateTransition(application, ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED);

        application.setStatus(ApplicationStatus.SUBMITTED);
        application.setSubmissionDate(LocalDate.now());

        application = applicationRepository.save(application);

        // Spawn the workflow instance — the engine now governs all future transitions
        workflowService.spawnApplicationWorkflow(application);

        log.info("Application submitted and workflow spawned: ref={}", application.getReference());
        auditService.log("APPLICATION_SUBMITTED", "InternshipApplication", id,
                Map.of("status", ApplicationStatus.DRAFT.toString()),
                Map.of("status", ApplicationStatus.SUBMITTED.toString()), actor.getId(), null);
        return ApplicationResponse.from(application);
    }

    /**
     * Candidate withdraws their own application (→ WITHDRAWN), only if not yet ACCEPTED.
     */
    @Transactional
    public ApplicationResponse withdrawApplication(UUID id, UserPrincipal actor) {
        InternshipApplication application = resolveOwnCandidateApplicationOrThrow(id, actor);

        if (application.getStatus() == ApplicationStatus.ACCEPTED) {
            throw new BusinessRuleException("APPLICATION_WITHDRAWAL_NOT_ALLOWED",
                    "An ACCEPTED application cannot be withdrawn.");
        }
        if (application.getStatus() == ApplicationStatus.WITHDRAWN) {
            throw new BusinessRuleException("APPLICATION_ALREADY_WITHDRAWN",
                    "This application is already withdrawn.");
        }

        application.setStatus(ApplicationStatus.WITHDRAWN);
        application = applicationRepository.save(application);
        log.info("Application withdrawn: ref={}", application.getReference());
        auditService.log("APPLICATION_WITHDRAWN", "InternshipApplication", id,
                Map.of("status", application.getStatus().toString()),
                Map.of("status", ApplicationStatus.WITHDRAWN.toString()), actor.getId(), null);
        return ApplicationResponse.from(application);
    }

    // -------------------------------------------------------------------------
    // Staff-facing operations
    // -------------------------------------------------------------------------
    //
    // NOTE (Phase A5): direct status mutations by staff have been replaced by
    // the Workflow Engine. Staff must now use:
    //   POST /api/applications/{id}/workflow/actions
    // with the appropriate WorkflowTransitionRequest body.
    //
    // The reviewApplication method has been removed. The WorkflowService
    // (WorkflowTransitionGuard + WorkflowAction audit) now owns all
    // SUBMITTED → UNDER_REVIEW → FINAL_DECISION transitions.

    /**
     * Candidate resubmits after corrections (NEEDS_CORRECTION → SUBMITTED).
     */
    @Transactional
    public ApplicationResponse resubmitApplication(UUID id, UserPrincipal actor) {
        InternshipApplication application = resolveOwnCandidateApplicationOrThrow(id, actor);
        validateTransition(application, ApplicationStatus.NEEDS_CORRECTION, ApplicationStatus.SUBMITTED);

        application.setStatus(ApplicationStatus.SUBMITTED);
        application.setSubmissionDate(LocalDate.now());
        application.setCorrectionComment(null);

        application = applicationRepository.save(application);
        log.info("Application resubmitted: ref={}", application.getReference());
        auditService.log("APPLICATION_RESUBMITTED", "InternshipApplication", id,
                Map.of("status", ApplicationStatus.NEEDS_CORRECTION.toString()),
                Map.of("status", ApplicationStatus.SUBMITTED.toString()), actor.getId(), null);
        return ApplicationResponse.from(application);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Generates a server-side reference in format {@code APP-YYYY-NNNNN}.
     */
    private String generateReference() {
        int year = Year.now().getValue();
        String prefix = "APP-" + year + "-";
        long count = applicationRepository.countByReferencePrefix(prefix);
        return String.format("%s%05d", prefix, count + 1);
    }

    /**
     * Validates that the application is currently in {@code expectedCurrent} before
     * moving to {@code target}. Throws {@link BusinessRuleException} otherwise.
     */
    private void validateTransition(InternshipApplication app,
                                    ApplicationStatus expectedCurrent,
                                    ApplicationStatus target) {
        if (app.getStatus() != expectedCurrent) {
            throw new BusinessRuleException("INVALID_STATUS_TRANSITION",
                    String.format("Cannot transition to %s from %s. Expected current status: %s.",
                            target, app.getStatus(), expectedCurrent));
        }
    }

    /**
     * Resolves an application with full IDOR protection:
     * - CANDIDATE: must own the application.
     * - ADMIN / HR: can access any.
     */
    private InternshipApplication resolveWithIdorCheck(UUID id, UserPrincipal actor) {
        if (actor.hasRole("ADMIN") || actor.hasRole("HR")) {
            return findApplicationOrThrow(id);
        }

        return applicationRepository.findByIdAndCandidateUserId(id, actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found or you do not have permission to view it."));
    }

    /**
     * Resolves an application that MUST belong to the calling candidate.
     * Always enforces ownership; used for mutating candidate operations.
     */
    private InternshipApplication resolveOwnCandidateApplicationOrThrow(UUID id, UserPrincipal actor) {
        return applicationRepository.findByIdAndCandidateUserId(id, actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found or you do not have permission to perform this action."));
    }

    private InternshipApplication findApplicationOrThrow(UUID id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + id));
    }

    private Candidate findCandidateByUserOrThrow(UUID userId) {
        return candidateRepository.findAll().stream()
                .filter(c -> c.getUser() != null && c.getUser().getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("CANDIDATE_PROFILE_REQUIRED",
                        "You must complete your candidate profile before submitting an application."));
    }
}
