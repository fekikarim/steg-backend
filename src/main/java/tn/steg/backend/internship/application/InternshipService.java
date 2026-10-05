package tn.steg.backend.internship.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.common.domain.event.InternshipAssignedEvent;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.application.dto.*;
import tn.steg.backend.internship.domain.model.*;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.internship.domain.service.InternshipClassificationResult;
import tn.steg.backend.internship.domain.service.InternshipClassificationService;
import tn.steg.backend.internship.domain.service.InternshipEligibilityService;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.domain.repository.DepartmentRepository;
import tn.steg.backend.organization.domain.repository.EmployeeRepository;
import tn.steg.backend.messaging.application.MessagingService;
import tn.steg.backend.audit.application.AuditService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class InternshipService {

    private final InternshipRepository internshipRepository;
    private final InternshipAssignmentRepository assignmentRepository;
    private final InternshipApplicationRepository applicationRepository;
    private final CandidateRepository candidateRepository;
    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final tn.steg.backend.companion.domain.repository.InternshipJournalRepository journalRepository;

    /**
     * S6b: the ONLY component allowed to change `internship.status`
     * (creation, §4 transitions and cancellation). Enforced by
     * `InternshipStatusAuthorityFitnessTest`.
     */
    private final InternshipLifecycleService internshipLifecycleService;

    /** Lazy: MessagingService depends on internship repositories but not on this service. */
    @Lazy
    private final MessagingService messagingService;

    /** Business facts for cross-cutting concerns; never a dependency on consumers (Phase A10). */
    private final ApplicationEventPublisher eventPublisher;

    private final AuditService auditService;
        private final SupervisionScopeService supervisionScopeService;

    private final InternshipClassificationService classificationService = new InternshipClassificationService();
    private final InternshipEligibilityService eligibilityService = new InternshipEligibilityService();

    public InternshipService(InternshipRepository internshipRepository,
                               InternshipAssignmentRepository assignmentRepository,
                               InternshipApplicationRepository applicationRepository,
                               CandidateRepository candidateRepository,
                               DepartmentRepository departmentRepository,
                               EmployeeRepository employeeRepository,
                               UserRepository userRepository,
                               RoleRepository roleRepository,
                               tn.steg.backend.companion.domain.repository.InternshipJournalRepository journalRepository,
                               InternshipLifecycleService internshipLifecycleService,
                               @Lazy MessagingService messagingService,
                               ApplicationEventPublisher eventPublisher,
                               AuditService auditService,
                               SupervisionScopeService supervisionScopeService) {
        this.internshipRepository  = internshipRepository;
        this.assignmentRepository  = assignmentRepository;
        this.applicationRepository = applicationRepository;
        this.candidateRepository   = candidateRepository;
        this.departmentRepository  = departmentRepository;
        this.employeeRepository    = employeeRepository;
        this.userRepository        = userRepository;
        this.roleRepository        = roleRepository;
        this.journalRepository     = journalRepository;
        this.internshipLifecycleService = internshipLifecycleService;
        this.messagingService      = messagingService;
        this.eventPublisher        = eventPublisher;
        this.auditService          = auditService;
        this.supervisionScopeService = supervisionScopeService;
    }

    // -------------------------------------------------------------------------
    // Creation
    // -------------------------------------------------------------------------

    /**
     * Create an Internship from an APPROVED InternshipApplication.
     */
    @Transactional
    public InternshipResponse createFromApplication(InternshipCreateFromApplicationRequest request, UserPrincipal actor) {
        InternshipApplication application = applicationRepository.findById(request.applicationId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + request.applicationId()));

        if (application.getStatus() != ApplicationStatus.APPROVED
                && application.getStatus() != ApplicationStatus.APPROVED) {
            throw new BusinessRuleException("APPLICATION_NOT_ACCEPTED",
                    "An internship can only be created from an APPROVED application. Current status: " + application.getStatus());
        }

        LocalDate start = application.getDesiredStartDate() != null ? application.getDesiredStartDate() : LocalDate.now();
        LocalDate end = application.getDesiredEndDate() != null ? application.getDesiredEndDate() : start.plusMonths(1);

        // E1.1: academic level is not captured on the application yet (see matrix);
        // duration + staff flag drive the derivation here.
        InternshipClassificationResult classification = classificationService.classify(
                start, end, request.observationObligatoire(), null
        );

        String reference = generateInternshipReference();
        Internship internship = new Internship(
                reference,
                application.getCandidate(),
                start,
                end,
                classification.type(),
                classification.requirement()
        );
        internship.setApplication(application);
        internshipLifecycleService.initializeStatus(internship);
        internship.setPlannedAt(Instant.now());
        User supervisor = resolveSupervisorOrAdmin(request.supervisorUserId(), actor);
        internship.setSupervisorUser(supervisor);

        internship = internshipRepository.save(internship);
        journalRepository.save(new tn.steg.backend.companion.domain.model.InternshipJournal(internship));

        elevateToIntern(application.getCandidate());
        log.info("Internship created from application: ref={}, candidate={}, supervisor={}", reference, application.getCandidate().getId(), supervisor != null ? supervisor.getId() : null);
        auditService.log("INTERNSHIP_CREATED_FROM_APPLICATION", "Internship", internship.getId(), null,
                Map.of("reference", reference, "applicationId", application.getId(),
                        "type", classification.type(), "requirement", classification.requirement()),
                actor.getId(), null);

        // S6b: no second engine. The internship status is owned by
        // InternshipLifecycleService only (POST /api/internships/{id}/status-transitions).
        return InternshipResponse.from(internship);
    }

    /**
     * Create an Internship manually for a candidate directly by staff.
     */
    @Transactional
    public InternshipResponse createManual(InternshipCreateManualRequest request, UserPrincipal actor) {
        Candidate candidate = candidateRepository.findById(request.candidateId())
                .orElseThrow(() -> new ResourceNotFoundException("Candidate not found: " + request.candidateId()));

        InternshipClassificationResult classification = classificationService.classify(
                request.startDate(),
                request.endDate(),
                request.observationObligatoire(),
                request.academicLevel()
        );

        String reference = generateInternshipReference();
        Internship internship = new Internship(
                reference,
                candidate,
                request.startDate(),
                request.endDate(),
                classification.type(),
                classification.requirement()
        );
        internship.setSubject(request.subject());
        internship.setAcademicLevel(request.academicLevel());
        internshipLifecycleService.initializeStatus(internship);
        internship.setPlannedAt(Instant.now());
        User supervisor = resolveSupervisorOrAdmin(request.supervisorUserId(), actor);
        internship.setSupervisorUser(supervisor);

        internship = internshipRepository.save(internship);
        journalRepository.save(new tn.steg.backend.companion.domain.model.InternshipJournal(internship));

        elevateToIntern(candidate);
        log.info("Internship created manually: ref={}, candidate={}, supervisor={}", reference, candidate.getId(), supervisor != null ? supervisor.getId() : null);
        auditService.log("INTERNSHIP_CREATED_MANUAL", "Internship", internship.getId(), null,
                Map.of("reference", reference, "candidateId", candidate.getId(),
                        "type", classification.type(), "requirement", classification.requirement()),
                actor.getId(), null);

        // S6b: no second engine for manually created internships either.
        return InternshipResponse.from(internship);
    }

    private User resolveSupervisorOrAdmin(UUID supervisorUserId, UserPrincipal actor) {
        if (supervisorUserId != null) {
            User user = userRepository.findById(supervisorUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("Supervisor not found: " + supervisorUserId));
            boolean isValid = user.getAssignedRoles().stream()
                    .anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getCode()) || "SUPERVISOR".equalsIgnoreCase(r.getCode()));
            if (!isValid) {
                throw new BusinessRuleException("INVALID_SUPERVISOR", "Selected user is not an Admin or Supervisor.");
            }
            return user;
        }
        if (actor != null && actor.getId() != null) {
            Optional<User> acting = userRepository.findById(actor.getId());
            if (acting.isPresent()) {
                return acting.get();
            }
        }
        return userRepository.findAll().stream()
                .filter(u -> u.getAssignedRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getCode())))
                .findFirst()
                .orElse(null);
    }

    /**
     * E2: the candidate becomes an intern when their internship is created. The INTERN
     * role is added alongside CANDIDATE (never replaced) so role-scoped surfaces
     * (mobile workspace, intern assistant) unlock on next login. Idempotent.
     */
    private void elevateToIntern(Candidate candidate) {
        if (candidate == null || candidate.getUser() == null) {
            return;
        }
        UUID userId = candidate.getUser().getId();
        tn.steg.backend.iam.domain.model.User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return;
        }
        boolean already = user.getAssignedRoles().stream()
                .anyMatch(r -> "INTERN".equalsIgnoreCase(r.getCode()));
        if (already) {
            return;
        }
        roleRepository.findByCode("INTERN").ifPresent(intern -> {
            user.getAssignedRoles().add(intern);
            userRepository.save(user);
            auditService.log("USER_ELEVATED_TO_INTERN", "User", userId, null,
                    Map.of("candidateId", candidate.getId().toString()), null, null);
            log.info("User elevated to INTERN: user={} candidate={}", userId, candidate.getId());
        });
    }

    // -------------------------------------------------------------------------
    // Dates Update & Reclassification
    // -------------------------------------------------------------------------
    @Transactional
    public InternshipResponse updateDates(UUID id, InternshipUpdateDatesRequest request, UserPrincipal actor) {
        Internship internship = findInternshipOrThrow(id);

        if (internship.getStatus() == InternshipStatus.VALIDATED || internship.getStatus() == InternshipStatus.CANCELLED) {
            throw new BusinessRuleException("INTERNSHIP_TERMINATED",
                    "Cannot update dates of an internship with status: " + internship.getStatus());
        }

        InternshipClassificationResult classification = classificationService.classify(
                request.startDate(),
                request.endDate(),
                request.observationObligatoire(),
                internship.getAcademicLevel()
        );

        Map<String, Object> oldValues = Map.of(
                "startDate", internship.getStartDate(), "endDate", internship.getEndDate(),
                "type", internship.getType(), "requirement", internship.getRequirement());

        internship.setStartDate(request.startDate());
        internship.setEndDate(request.endDate());
        internship.setType(classification.type());
        internship.setRequirement(classification.requirement());

        internship = internshipRepository.save(internship);
        log.info("Internship dates updated & reclassified: ref={}, type={}, req={}",
                internship.getReference(), classification.type(), classification.requirement());
        auditService.log("INTERNSHIP_REQUIREMENT_CHANGED", "Internship", id,
                oldValues,
                Map.of("startDate", request.startDate(), "endDate", request.endDate(),
                        "type", classification.type(), "requirement", classification.requirement()),
                actor.getId(), null);
        return InternshipResponse.from(internship);
    }

    // -------------------------------------------------------------------------
    // Assignments (Single ACTIVE assignment invariant)
    // -------------------------------------------------------------------------

    @Transactional
    public InternshipAssignmentResponse assign(UUID internshipId, InternshipAssignmentRequest request, UserPrincipal actor) {
        Internship internship = findInternshipOrThrow(internshipId);

        if (internship.getStatus() == InternshipStatus.VALIDATED || internship.getStatus() == InternshipStatus.CANCELLED) {
            throw new BusinessRuleException("INTERNSHIP_TERMINATED",
                    "Cannot assign an internship with status: " + internship.getStatus());
        }

        Department destination = departmentRepository.findById(request.departmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + request.departmentId()));

        User supervisorUser = userRepository.findById(request.supervisorId()).orElse(null);
        Employee supervisor = supervisorUser == null
                ? employeeRepository.findById(request.supervisorId()).orElse(null)
                : null;
        if (supervisorUser == null && supervisor == null) {
            throw new ResourceNotFoundException("Supervisor not found: " + request.supervisorId());
        }

        Employee assignedBy = findEmployeeByUser(actor.getId())
                .orElse(null);
        User assignedByUser = userRepository.findById(actor.getId()).orElse(null);

        // Check for existing ACTIVE assignment. Atomically terminate it before creating new one
        Optional<InternshipAssignment> currentActiveOpt = assignmentRepository
                .findByInternshipIdAndStatus(internshipId, AssignmentStatus.ACTIVE);

        Map<String, Object> previousAssignment = null;
        if (currentActiveOpt.isPresent()) {
            InternshipAssignment currentActive = currentActiveOpt.get();
            UUID previousSupervisorId = currentActive.getSupervisorUser() != null
                    ? currentActive.getSupervisorUser().getId()
                    : currentActive.getSupervisor() != null && currentActive.getSupervisor().getUser() != null
                            ? currentActive.getSupervisor().getUser().getId()
                            : null;
            previousAssignment = Map.of(
                    "supervisorId", previousSupervisorId != null ? previousSupervisorId : "unknown",
                    "departmentId", currentActive.getDestination().getId());
            currentActive.setStatus(AssignmentStatus.REASSIGNED);
            currentActive.setEndedAt(Instant.now());
            assignmentRepository.saveAndFlush(currentActive);
            log.info("Previous active assignment {} marked as REASSIGNED for internship {}",
                    currentActive.getId(), internship.getReference());
        }

        LocalDate start = request.startDate() != null ? request.startDate() : internship.getStartDate();
        LocalDate end = request.endDate() != null ? request.endDate() : internship.getEndDate();

        InternshipAssignment newAssignment = new InternshipAssignment(
                internship,
                destination,
                supervisor,
                assignedBy,
                LocalDate.now(),
                start,
                end,
                AssignmentStatus.ACTIVE
        );
        newAssignment.setAssignmentReason(request.assignmentReason());
        newAssignment.setSupervisorUser(supervisorUser != null ? supervisorUser : supervisor.getUser());
        newAssignment.setAssignedByUser(assignedByUser);
        internship.setSupervisorUser(supervisorUser != null ? supervisorUser : supervisor.getUser());

        newAssignment = assignmentRepository.save(newAssignment);
        auditService.log("INTERNSHIP_ASSIGNMENT_ASSIGNED", "InternshipAssignment", newAssignment.getId(),
                previousAssignment,
                Map.of("internshipId", internshipId, "supervisorId", request.supervisorId(),
                        "departmentId", destination.getId(), "status", AssignmentStatus.ACTIVE),
                actor.getId(), null);

        // Assigning a supervisor starts the internship — through the single
        // authority, so the §4 matrix, the capability rule, the audit entry and
        // the notification all happen exactly like a manual transition (S6b).
        if (internship.getStatus() == InternshipStatus.APPROVED) {
            internshipLifecycleService.transition(internshipId, InternshipStatus.IN_PROGRESS,
                    "Started automatically when the supervisor assignment became active", actor);
            internship.setActivatedAt(Instant.now());
            internshipRepository.save(internship);
        }

        // Phase A9: auto-create/reuse the PRIVATE Intern↔Supervisor thread
        // (Internship.privateThread 0..1, backed by conversations.internship_id
        // with a partial unique index for type='PRIVATE'). Best-effort: a
        // messaging failure must never roll back the assignment itself.
        try {
            User effectiveSupervisorUser = newAssignment.getSupervisorUser();
            if (internship.getCandidate() != null && internship.getCandidate().getUser() != null
                    && effectiveSupervisorUser != null) {
                messagingService.ensurePrivateThread(
                        internship,
                        internship.getCandidate().getUser(),
                        effectiveSupervisorUser);
            } else {
                log.warn("Skipping private thread creation for internship {}: missing intern/supervisor user link",
                        internship.getReference());
            }
        } catch (Exception e) {
            log.error("Failed to ensure private thread for internship {}: {}",
                    internship.getReference(), e.getMessage());
        }

        // Phase A10: notify intern + supervisor (consumed AFTER_COMMIT).
        if (internship.getCandidate() != null && internship.getCandidate().getUser() != null
                && newAssignment.getSupervisorUser() != null) {
            eventPublisher.publishEvent(new InternshipAssignedEvent(
                    internship.getId(),
                    internship.getReference(),
                    internship.getCandidate().getUser().getId(),
                    newAssignment.getSupervisorUser().getId(),
                    destination.getName(),
                    actor.getId()));
        }

        return InternshipAssignmentResponse.from(newAssignment);
    }

    @Transactional(readOnly = true)
    public List<InternshipAssignmentResponse> listAssignments(UUID internshipId) {
        findInternshipOrThrow(internshipId);
        // A14 N+1 fix: department/supervisor/assigner fetched in the query itself.
        return assignmentRepository.findByInternshipIdWithDetails(internshipId).stream()
                .map(InternshipAssignmentResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Queries & Transparency
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
        public List<InternshipResponse> listInternships(UserPrincipal actor) {
                List<Internship> internships = actor != null && actor.hasRole("ADMIN")
                                ? internshipRepository.findAllWithDetails()
                                : supervisionScopeService.assignedInternships(actor);
                return internships.stream()
                .map(InternshipResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InternshipResponse> listInternships() {
        return internshipRepository.findAllWithDetails().stream()
                .map(InternshipResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public InternshipResponse getInternship(UUID id, UserPrincipal actor) {
        Internship internship = findInternshipOrThrow(id);
        // Row-level scope (§3.4): out-of-scope reads are 404, never 403, so a
        // supervisor/candidate cannot learn that a foreign internship exists.
        if (actor != null && actor.hasRole("ADMIN")) {
            return InternshipResponse.from(internship);
        }
        boolean assigned = actor != null && supervisionScopeService.isAssignedTo(actor, id);
        boolean owner = actor != null && internship.getCandidate() != null
                && internship.getCandidate().getUser() != null
                && actor.getId().equals(internship.getCandidate().getUser().getId());
        if (!assigned && !owner) {
            throw new ResourceNotFoundException("Internship not found: " + id);
        }
        return InternshipResponse.from(internship);
    }

    @Transactional(readOnly = true)
    public InternshipClassificationResponse getClassification(UUID id) {
        Internship internship = findInternshipOrThrow(id);
        Boolean obsObligatoire = internship.getRequirement() == InternshipRequirement.OBLIGATOIRE;
        InternshipClassificationResult result = classificationService.classify(
                internship.getStartDate(),
                internship.getEndDate(),
                obsObligatoire,
                internship.getAcademicLevel()
        );
        return InternshipClassificationResponse.from(
                internship.getId(),
                internship.getReference(),
                internship.getStartDate(),
                internship.getEndDate(),
                result
        );
    }

    @Transactional
    public InternshipResponse cancelInternship(UUID id, UserPrincipal actor) {
        // S6b: cancellation is a status change too — the authority owns it
        // (rule + capability + audit), this method only delegates.
        return internshipLifecycleService.cancel(id, actor);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Internship findInternshipOrThrow(UUID id) {
        return internshipRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + id));
    }

    private Optional<Employee> findEmployeeByUser(UUID userId) {
        return employeeRepository.findAll().stream()
                .filter(e -> e.getUser() != null && e.getUser().getId().equals(userId))
                .findFirst();
    }

    private synchronized String generateInternshipReference() {
        int currentYear = Year.now().getValue();
        String prefix = String.format("INT-%d-", currentYear);
        long count = internshipRepository.countByReferencePrefix(prefix);
        long sequence = count + 1;
        String reference;
        do {
            reference = String.format("INT-%d-%05d", currentYear, sequence);
            sequence++;
        } while (internshipRepository.existsByReference(reference));
        return reference;
    }
}
