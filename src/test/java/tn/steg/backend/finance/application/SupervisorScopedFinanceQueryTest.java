package tn.steg.backend.finance.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.finance.domain.model.FinanceCase;
import tn.steg.backend.finance.domain.model.FinanceCaseStatus;
import tn.steg.backend.finance.infrastructure.persistence.FinanceCaseRepository;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.infrastructure.persistence.InternshipAssignmentRepository;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supervisor scope of the finance queue (AGENTS.md §5.6 scoping).
 *
 * <p>Regression guard for the implicit-join defect: {@code a.supervisor.user.id}
 * compiles to an INNER JOIN, so once assignments became user-backed (legacy
 * {@code supervisor_id} NULL, V36) the supervisor query returned an EMPTY page
 * for every supervisor. Both the plain list and the status-filtered list must
 * resolve the user-backed link AND still hide other supervisors' cases.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Finance — supervisor scope follows the user-backed assignment link")
class SupervisorScopedFinanceQueryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipAssignmentRepository assignmentRepository;
    @Autowired private FinanceCaseRepository financeCaseRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private String run;
    private User supervisorA;
    private User supervisorB;
    private Department department;
    private University university;
    private FinanceCase caseA;
    private FinanceCase caseB;

    @BeforeEach
    void setUp() {
        run = UUID.randomUUID().toString().substring(0, 8);
        supervisorA = newSupervisor("supA");
        supervisorB = newSupervisor("supB");
        department = departmentRepository.saveAndFlush(
                new Department("DEPT_FIN_" + run, "Dept Fin " + run, "IT"));
        university = universityRepository.saveAndFlush(
                new University("UNI_FIN_" + run, "Uni Fin " + run));

        caseA = financeCaseRepository.saveAndFlush(new FinanceCase(
                "FC-" + run + "-A", assignedInternship("INT-" + run + "-A", supervisorA)));
        caseB = financeCaseRepository.saveAndFlush(new FinanceCase(
                "FC-" + run + "-B", assignedInternship("INT-" + run + "-B", supervisorB)));
        caseB.setStatus(FinanceCaseStatus.READY_FOR_DECISION);
        financeCaseRepository.saveAndFlush(caseB);
    }

    @Test
    @DisplayName("a supervisor sees the case of a user-backed assignment (not an empty page)")
    void supervisorSeesOwnCaseThroughUserBackedAssignment() {
        Page<FinanceCase> page = scoped(supervisorA, null);

        assertThat(page.getContent())
                .extracting(FinanceCase::getId)
                .containsExactly(caseA.getId())
                .doesNotContain(caseB.getId());
    }

    @Test
    @DisplayName("the status filter keeps the user-backed supervisor scope")
    void statusFilteredListKeepsUserBackedScope() {
        assertThat(scoped(supervisorA, FinanceCaseStatus.OPENED).getContent())
                .extracting(FinanceCase::getId)
                .containsExactly(caseA.getId());
        assertThat(scoped(supervisorA, FinanceCaseStatus.READY_FOR_DECISION).getContent()).isEmpty();
    }

    @Test
    @DisplayName("a supervisor never sees another supervisor's case")
    void supervisorCannotSeeAnotherSupervisorsCase() {
        assertThat(scoped(supervisorB, null).getContent())
                .extracting(FinanceCase::getId)
                .containsExactly(caseB.getId())
                .doesNotContain(caseA.getId());
    }

    // --- helpers -------------------------------------------------------------

    /** The infrastructure repository extends both JpaRepository and the domain
     *  port, so saveAndFlush is ambiguous without an explicit view. */
    private tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository assignmentPort() {
        return assignmentRepository;
    }

    private Page<FinanceCase> scoped(User supervisor, FinanceCaseStatus status) {
        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "openedAt"));
        return status == null
                ? financeCaseRepository.findAllForSupervisor(supervisor.getId(), pageable)
                : financeCaseRepository.findByStatusForSupervisor(status, supervisor.getId(), pageable);
    }

    /** Internship with an ACTIVE, user-backed assignment (legacy supervisor_id NULL). */
    private Internship assignedInternship(String reference, User supervisor) {
        Candidate candidate = new Candidate(
                "Candidat", reference, reference.toLowerCase() + "_" + run + "@steg.tn",
                java.util.Base64.getEncoder().encodeToString(reference.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                university);
        candidate = candidateRepository.saveAndFlush(candidate);

        Internship internship = internshipRepository.saveAndFlush(new Internship(
                reference, candidate, LocalDate.now(), LocalDate.now().plusMonths(2),
                InternshipType.PERFECTIONNEMENT, InternshipRequirement.OBLIGATOIRE));
        internship.setStatus(InternshipStatus.IN_PROGRESS);
        internship.setSupervisorUser(supervisor);
        internship = internshipRepository.saveAndFlush(internship);

        InternshipAssignment assignment = new InternshipAssignment();
        assignment.setInternship(internship);
        assignment.setDestination(department);
        assignment.setSupervisorUser(supervisor);
        assignment.setAssignedByUser(supervisor);
        assignment.setAssignedAt(LocalDate.now());
        assignment.setStartDate(internship.getStartDate());
        assignment.setEndDate(internship.getEndDate());
        assignment.setStatus(AssignmentStatus.ACTIVE);
        assignmentPort().saveAndFlush(assignment);
        return internship;
    }

    private User newSupervisor(String prefix) {
        User user = userRepository.saveAndFlush(
                new User(prefix + "_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        Role role = roleRepository.findByCode("SUPERVISOR").orElse(null);
        if (role != null) {
            user.getAssignedRoles().add(role);
            user = userRepository.saveAndFlush(user);
        }
        return user;
    }
}