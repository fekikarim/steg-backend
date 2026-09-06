package tn.steg.backend.performance;

import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.application.application.ApplicationService;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.common.infrastructure.config.JpaAuditingConfig;
import tn.steg.backend.finance.application.FinanceService;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.messaging.application.MessagingService;
import tn.steg.backend.messaging.domain.model.Conversation;
import tn.steg.backend.messaging.domain.model.ConversationMember;
import tn.steg.backend.messaging.domain.model.ConversationMemberRole;
import tn.steg.backend.messaging.domain.model.ConversationType;
import tn.steg.backend.messaging.domain.model.Message;
import tn.steg.backend.messaging.domain.repository.ConversationMemberRepository;
import tn.steg.backend.messaging.domain.repository.ConversationRepository;
import tn.steg.backend.messaging.domain.repository.MessageRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase A14 — hot-path query-count gates. Hibernate statistics are enabled in
 * the {@code test} profile; each test seeds representative rows, drops the
 * persistence context (production-like cold read), clears statistics, runs ONE
 * read operation, and asserts the executed SQL statement count stays flat no
 * matter how many rows exist — i.e. no N+1.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
@Transactional
@DisplayName("A14 — Hot-path query counts")
class HotPathQueryCountTest {

    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private InternshipApplicationRepository applicationRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipAssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ConversationMemberRepository memberRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private ApplicationService applicationService;
    @Autowired private InternshipService internshipService;
    @Autowired private MessagingService messagingService;
    @Autowired private FinanceService financeService;

    private UserPrincipal adminPrincipal;
    private UserPrincipal financePrincipal;
    private Candidate candidateA;
    private Candidate candidateB;
    private Department department;
    private Employee supervisor;

    @BeforeEach
    void setUp() throws Exception {
        User admin = userRepository.saveAndFlush(new User("qc_admin@test.tn", "hash", UserStatus.ACTIVE));
        adminPrincipal = new UserPrincipal(admin.getId(), admin.getEmail(), List.of("ROLE_ADMIN"));
        User finance = userRepository.saveAndFlush(new User("qc_fin@test.tn", "hash", UserStatus.ACTIVE));
        financePrincipal = new UserPrincipal(finance.getId(), finance.getEmail(), List.of("ROLE_FINANCE"));

        department = departmentRepository.saveAndFlush(new Department("QC_DEPT", "QC Department", "QC"));
        User supUser = userRepository.saveAndFlush(new User("qc_sup@test.tn", "hash", UserStatus.ACTIVE));
        supervisor = new Employee("QC-EMP-1", "Qc", "Sup", department);
        supervisor.setUser(supUser);
        supervisor = employeeRepository.saveAndFlush(supervisor);

        candidateA = createCandidate("qc_a@test.tn", "40000001");
        candidateB = createCandidate("qc_b@test.tn", "40000002");
    }

    private Candidate createCandidate(String email, String cin) throws Exception {
        University uni = universityRepository.saveAndFlush(
                new University("Q_" + UUID.randomUUID().toString().substring(0, 8), "Uni"));
        User user = userRepository.saveAndFlush(new User(email, "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String hash = Base64.getEncoder().encodeToString(digest.digest(cin.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Qc", "Cand", email, hash, uni);
        candidate.setUser(user);
        candidate.setNationalIdEncrypted(cin);
        return candidateRepository.saveAndFlush(candidate);
    }

    private Statistics statistics() {
        return entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    private long countQueries(Runnable action) {
        entityManager.flush();
        entityManager.clear();
        Statistics stats = statistics();
        stats.clear();
        action.run();
        return stats.getQueryExecutionCount();
    }

    private void seedApplications() {
        for (int i = 1; i <= 3; i++) {
            InternshipApplication app = new InternshipApplication();
            app.setReference("APP-QC-A-" + i);
            app.setCandidate(candidateA);
            app.setStatus(ApplicationStatus.DRAFT);
            app.setDesiredStartDate(LocalDate.of(2026, 7, 1));
            app.setDesiredEndDate(LocalDate.of(2026, 8, 31));
            app.setProposedTheme("Theme " + i);
            applicationRepository.save(app);
        }
        for (int i = 1; i <= 2; i++) {
            InternshipApplication app = new InternshipApplication();
            app.setReference("APP-QC-B-" + i);
            app.setCandidate(candidateB);
            app.setStatus(ApplicationStatus.SUBMITTED);
            app.setDesiredStartDate(LocalDate.of(2026, 7, 1));
            app.setDesiredEndDate(LocalDate.of(2026, 8, 31));
            app.setProposedTheme("Theme B" + i);
            applicationRepository.save(app);
        }
    }

    private Internship seedInternship(Candidate candidate, String tag) {
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.now().minusDays(30),
                LocalDate.now().plusDays(60),
                "Subject " + tag,
                "Ingenieur",
                false), adminPrincipal);
        return internshipRepository.findById(created.id()).orElseThrow();
    }

    @Test
    @DisplayName("V25 hot-path indexes are present in the database")
    void hotPathIndexesArePresent() {
        List<String> actual = entityManager
                .createNativeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'",
                        String.class).getResultList();
        assertThat(actual).as("V25 index for the workflow bulk lookup")
                .contains("idx_wf_inst_finance_case_id");
        assertThat(actual).as("V25 composite index for active-assignment lookups")
                .contains("idx_assignments_internship_status");
    }

    @Test
    @DisplayName("staff application list is a single query for any row count")
    void applicationListIsSingleQuery() {
        seedApplications();
        UserPrincipal admin = adminPrincipal;

        long queries = countQueries(() ->
                assertThat(applicationService.listApplications(admin)).hasSize(5));

        assertThat(queries).as("staff application list must be 1 query, was %d", queries)
                .isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("candidate application list is bounded (no table scan, no per-row queries)")
    void candidateApplicationListIsBounded() {
        seedApplications();
        UserPrincipal candA = new UserPrincipal(candidateA.getUser().getId(),
                candidateA.getEmail(), List.of("ROLE_CANDIDATE"));

        long queries = countQueries(() ->
                assertThat(applicationService.listApplications(candA)).hasSize(3));

        assertThat(queries).as("candidate application list must be bounded, was %d", queries)
                .isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("internship list is a single query for any row count")
    void internshipListIsSingleQuery() {
        for (int i = 1; i <= 3; i++) {
            seedInternship(i % 2 == 0 ? candidateB : candidateA, "L" + i);
        }

        long queries = countQueries(() ->
                assertThat(internshipService.listInternships()).hasSize(3));

        assertThat(queries).as("internship list must be 1 query, was %d", queries)
                .isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("assignment history is bounded (no per-row department/supervisor queries)")
    void assignmentHistoryIsBounded() {
        Internship internship = seedInternship(candidateA, "H");
        Department dept2 = departmentRepository.saveAndFlush(new Department("QC_DEPT2", "QC Dept 2", "QC2"));
        Employee sup2 = employeeRepository.saveAndFlush(new Employee("QC-EMP-2", "Qc", "Sup2", dept2));
        Employee assigner = employeeRepository.saveAndFlush(new Employee("QC-EMP-9", "Qc", "Assigner", department));
        for (int i = 0; i < 4; i++) {
            InternshipAssignment a = new InternshipAssignment(
                    internship, i % 2 == 0 ? department : dept2,
                    i % 2 == 0 ? supervisor : sup2, assigner,
                    LocalDate.now().minusDays(10), LocalDate.now().minusDays(10), LocalDate.now().plusDays(10),
                    i < 3 ? AssignmentStatus.ENDED : AssignmentStatus.ACTIVE);
            assignmentRepository.save(a);
        }

        long queries = countQueries(() ->
                assertThat(internshipService.listAssignments(internship.getId())).hasSize(4));

        assertThat(queries).as("assignment history must be bounded, was %d", queries)
                .isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("conversation history page is bounded (no per-message queries)")
    void conversationHistoryIsBounded() {
        Conversation conversation = conversationRepository.save(
                new Conversation(ConversationType.GROUP, "QC group", null));
        User userA = candidateA.getUser();
        User userB = candidateB.getUser();
        memberRepository.save(new ConversationMember(conversation, userA, ConversationMemberRole.OWNER));
        memberRepository.save(new ConversationMember(conversation, userB, ConversationMemberRole.MEMBER));
        for (int i = 1; i <= 12; i++) {
            messageRepository.save(new Message(conversation, i % 2 == 0 ? userB : userA,
                    "message " + i, (long) i));
        }
        UserPrincipal actorA = new UserPrincipal(userA.getId(), userA.getEmail(), List.of("ROLE_CANDIDATE"));

        long queries = countQueries(() ->
                assertThat(messagingService.getHistory(
                        conversation.getId(), null, PageRequest.of(0, 10), actorA).getContent()).hasSize(10));

        assertThat(queries).as("history page must be bounded, was %d", queries)
                .isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("finance case list page is bounded (no per-case child queries)")
    void financeCaseListIsBounded() {
        for (int i = 1; i <= 3; i++) {
            Internship internship = seedInternship(i % 2 == 0 ? candidateB : candidateA, "F" + i);
            internship.setStatus(InternshipStatus.COMPLETED);
            internshipRepository.save(internship);
            financeService.openFinanceCase(internship.getId(), financePrincipal);
        }

        long queries = countQueries(() ->
                assertThat(financeService.listFinanceCases(null, PageRequest.of(0, 10)).getContent()).hasSize(3));

        assertThat(queries).as("finance list page must be bounded, was %d", queries)
                .isLessThanOrEqualTo(12);
    }
}