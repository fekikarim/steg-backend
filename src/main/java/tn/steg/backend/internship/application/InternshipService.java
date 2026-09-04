package tn.steg.backend.internship.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InternshipService {

    private final InternshipRepository internshipRepository;
    private final InternshipAssignmentRepository assignmentRepository;
    private final InternshipApplicationRepository applicationRepository;
    private final CandidateRepository candidateRepository;
    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;

    private final InternshipClassificationService classificationService = new InternshipClassificationService();
    private final InternshipEligibilityService eligibilityService = new InternshipEligibilityService();

    // -------------------------------------------------------------------------
    // Creation
    // -------------------------------------------------------------------------

    /**
     * Create an Internship from an ACCEPTED InternshipApplication.
     */
    @Transactional
    public InternshipResponse createFromApplication(InternshipCreateFromApplicationRequest request, UserPrincipal actor) {
        InternshipApplication application = applicationRepository.findById(request.applicationId())
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + request.applicationId()));

        if (application.getStatus() != ApplicationStatus.ACCEPTED) {
            throw new BusinessRuleException("APPLICATION_NOT_ACCEPTED",
                    "An internship can only be created from an ACCEPTED application. Current status: " + application.getStatus());
        }

        LocalDate start = application.getDesiredStartDate() != null ? application.getDesiredStartDate() : LocalDate.now();
        LocalDate end = application.getDesiredEndDate() != null ? application.getDesiredEndDate() : start.plusMonths(1);

        InternshipClassificationResult classification = classificationService.classify(
                start, end, request.observationObligatoire()
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
        internship.setSubject(application.getProposedTheme());
        internship.setStatus(InternshipStatus.PLANNED);
        internship.setPlannedAt(Instant.now());

        internship = internshipRepository.save(internship);
        log.info("Internship created from application: ref={}, candidate={}", reference, application.getCandidate().getId());

        // Extension point: triggering InternshipWorkflowInstance in Phase A5
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
                request.observationObligatoire()
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
        internship.setStatus(InternshipStatus.PLANNED);
        internship.setPlannedAt(Instant.now());

        internship = internshipRepository.save(internship);
        log.info("Internship created manually: ref={}, candidate={}", reference, candidate.getId());

        return InternshipResponse.from(internship);
    }

    // -------------------------------------------------------------------------
    // Dates Update & Reclassification
    // -------------------------------------------------------------------------

    @Transactional
    public InternshipResponse updateDates(UUID id, InternshipUpdateDatesRequest request, UserPrincipal actor) {
        Internship internship = findInternshipOrThrow(id);

        if (internship.getStatus() == InternshipStatus.COMPLETED || internship.getStatus() == InternshipStatus.CANCELLED) {
            throw new BusinessRuleException("INTERNSHIP_TERMINATED",
                    "Cannot update dates of an internship with status: " + internship.getStatus());
        }

        InternshipClassificationResult classification = classificationService.classify(
                request.startDate(),
                request.endDate(),
                request.observationObligatoire()
        );

        internship.setStartDate(request.startDate());
        internship.setEndDate(request.endDate());
        internship.setType(classification.type());
        internship.setRequirement(classification.requirement());

        internship = internshipRepository.save(internship);
        log.info("Internship dates updated & reclassified: ref={}, type={}, req={}",
                internship.getReference(), classification.type(), classification.requirement());

        return InternshipResponse.from(internship);
    }

    // -------------------------------------------------------------------------
    // Assignments (Single ACTIVE assignment invariant)
    // -------------------------------------------------------------------------

    @Transactional
    public InternshipAssignmentResponse assign(UUID internshipId, InternshipAssignmentRequest request, UserPrincipal actor) {
        Internship internship = findInternshipOrThrow(internshipId);

        if (internship.getStatus() == InternshipStatus.COMPLETED || internship.getStatus() == InternshipStatus.CANCELLED) {
            throw new BusinessRuleException("INTERNSHIP_TERMINATED",
                    "Cannot assign an internship with status: " + internship.getStatus());
        }

        Department destination = departmentRepository.findById(request.departmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + request.departmentId()));

        Employee supervisor = employeeRepository.findById(request.supervisorId())
                .orElseThrow(() -> new ResourceNotFoundException("Supervisor not found: " + request.supervisorId()));

        Employee assignedBy = findEmployeeByUser(actor.getId())
                .orElse(supervisor); // fallback if actor employee profile not linked

        // Check for existing ACTIVE assignment. Atomically terminate it before creating new one
        Optional<InternshipAssignment> currentActiveOpt = assignmentRepository
                .findByInternshipIdAndStatus(internshipId, AssignmentStatus.ACTIVE);

        if (currentActiveOpt.isPresent()) {
            InternshipAssignment currentActive = currentActiveOpt.get();
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

        newAssignment = assignmentRepository.save(newAssignment);

        // If internship was PLANNED, update to ACTIVE
        if (internship.getStatus() == InternshipStatus.PLANNED) {
            internship.setStatus(InternshipStatus.ACTIVE);
            internship.setActivatedAt(Instant.now());
            internshipRepository.save(internship);
        }

        return InternshipAssignmentResponse.from(newAssignment);
    }

    @Transactional(readOnly = true)
    public List<InternshipAssignmentResponse> listAssignments(UUID internshipId) {
        findInternshipOrThrow(internshipId);
        return assignmentRepository.findByInternshipId(internshipId).stream()
                .map(InternshipAssignmentResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Queries & Transparency
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InternshipResponse> listInternships() {
        return internshipRepository.findAll().stream()
                .map(InternshipResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public InternshipResponse getInternship(UUID id) {
        return InternshipResponse.from(findInternshipOrThrow(id));
    }

    @Transactional(readOnly = true)
    public InternshipClassificationResponse getClassification(UUID id) {
        Internship internship = findInternshipOrThrow(id);
        Boolean obsObligatoire = internship.getRequirement() == InternshipRequirement.OBLIGATOIRE;
        InternshipClassificationResult result = classificationService.classify(
                internship.getStartDate(),
                internship.getEndDate(),
                obsObligatoire
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
        Internship internship = findInternshipOrThrow(id);
        if (internship.getStatus() == InternshipStatus.COMPLETED) {
            throw new BusinessRuleException("INTERNSHIP_ALREADY_COMPLETED", "Completed internship cannot be cancelled.");
        }
        internship.setStatus(InternshipStatus.CANCELLED);
        internship.setCancelledAt(Instant.now());
        return InternshipResponse.from(internshipRepository.save(internship));
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
