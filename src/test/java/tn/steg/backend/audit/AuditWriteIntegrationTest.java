package tn.steg.backend.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.application.application.ApplicationService;
import tn.steg.backend.application.application.dto.ApplicationCreateRequest;
import tn.steg.backend.audit.domain.repository.AuditLogRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.common.infrastructure.config.JpaAuditingConfig;
import tn.steg.backend.companion.application.CompanionService;
import tn.steg.backend.companion.application.dto.JournalEntryRequest;
import tn.steg.backend.companion.application.dto.ValidationRequest;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;
import tn.steg.backend.workflow.application.dto.WorkflowTransitionRequest;
import tn.steg.backend.workflow.application.WorkflowService;
import tn.steg.backend.workflow.domain.model.ApprovalDecision;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase A13 — proves the §89 mandatorily-audited actions actually write
 * {@code AuditLog} rows through the centralized writer when the real services
 * execute. Each step asserts the exact expected action code.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
@Transactional
@DisplayName("A13 — Audit write integration for §89 mandatory actions")
class AuditWriteIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    @Autowired private InternshipService internshipService;
    @Autowired private ApplicationService applicationService;
    @Autowired private CompanionService companionService;
    @Autowired private WorkflowService workflowService;

    private UserPrincipal admin;
    private UserPrincipal candidateActor;
    private UserPrincipal supervisorActor;

    private Candidate internCandidate;
    private java.util.UUID internshipId;

    @BeforeEach
    void setUp() {
        User adminUser = userRepository.saveAndFlush(new User("audit_admin@steg.tn", "hash", UserStatus.ACTIVE));
        admin = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        University uni = universityRepository.saveAndFlush(new University("AUDIT-UNI", "Audit University"));
        User internUser = userRepository.saveAndFlush(new User("audit_intern@steg.tn", "hash", UserStatus.ACTIVE));
        candidateActor = new UserPrincipal(internUser.getId(), internUser.getEmail(), List.of("ROLE_CANDIDATE"));
        internCandidate = new Candidate("Audit", "Intern", "audit_intern@steg.tn", "AUDIT-CIN-HASH", uni);
        internCandidate.setUser(internUser);
        internCandidate = candidateRepository.saveAndFlush(internCandidate);

        User supUser = userRepository.saveAndFlush(new User("audit_sup@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorActor = new UserPrincipal(supUser.getId(), supUser.getEmail(), List.of("ROLE_SUPERVISOR"));
        Department dept = departmentRepository.saveAndFlush(new Department("AUDIT-DEV", "Audit Dev", "tests"));
        Employee supervisorEmployee = new Employee("EMP-AUDIT-1", "Super", "Viseur", dept);
        supervisorEmployee.setUser(supUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                internCandidate.getId(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31),
                "Audit Project", "Ingénieur", false
        ), admin);
        internshipId = created.id();

        internshipService.assign(internshipId, new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31), "Audit assignment"
        ), admin);
    }

    @Test
    @DisplayName("internship assignment is audited (INTERNSHIP_ASSIGNMENT_ASSIGNED)")
    void assignmentIsAudited() {
        assertThat(auditLogRepository.findByAction("INTERNSHIP_ASSIGNMENT_ASSIGNED",
                org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isPositive();
    }

    @Test
    @DisplayName("journal validation is audited (JOURNAL_ENTRY_VALIDATED)")
    void journalValidationIsAudited() {
        var entryId = companionService.createJournalEntry(internshipId,
                new JournalEntryRequest("Day 1", "Audit journal entry", LocalDate.now()), candidateActor).id();
        companionService.submitJournalEntry(entryId, candidateActor);
        companionService.validateJournalEntry(entryId, new ValidationRequest("Looks good"), supervisorActor);

        assertThat(auditLogRepository.findByAction("JOURNAL_ENTRY_VALIDATED",
                org.springframework.data.domain.Pageable.unpaged()).getContent())
                .anyMatch(e -> e.getEntityId().equals(entryId));
    }

    @Test
    @DisplayName("deliverable validation is audited (DELIVERABLE_VALIDATED)")
    void deliverableValidationIsAudited() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "rapport.pdf",
                "application/pdf", "%PDF-1.4 audit deliverable".getBytes(StandardCharsets.UTF_8));
        var deliverableId = companionService.createDeliverable(internshipId, "Rapport final", "Audit PDF",
                file, candidateActor).id();
        companionService.submitDeliverable(deliverableId, candidateActor);
        companionService.validateDeliverable(deliverableId, new ValidationRequest("Validated"), supervisorActor);

        assertThat(auditLogRepository.findByAction("DELIVERABLE_VALIDATED",
                org.springframework.data.domain.Pageable.unpaged()).getContent())
                .anyMatch(e -> e.getEntityId().equals(deliverableId));
    }

    @Test
    @DisplayName("application acceptance is audited (APPLICATION_ACCEPTED) through the workflow engine")
    void applicationAcceptanceIsAudited() {
        var applicationId = applicationService.createApplication(
                new ApplicationCreateRequest(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                        "PFE Audit Theme", true), candidateActor).id();
        applicationService.submitApplication(applicationId, candidateActor);
        workflowService.transitionApplication(applicationId,
                new WorkflowTransitionRequest("UNDER_REVIEW", WorkflowActionType.VALIDATION, null, null), admin);
        workflowService.transitionApplication(applicationId,
                new WorkflowTransitionRequest("FINAL_DECISION", WorkflowActionType.APPROVAL,
                        ApprovalDecision.APPROVED, "Accepted for PFE"), admin);

        assertThat(auditLogRepository.findByAction("APPLICATION_ACCEPTED",
                org.springframework.data.domain.Pageable.unpaged()).getContent())
                .anyMatch(e -> e.getEntityId().equals(applicationId));
    }
}