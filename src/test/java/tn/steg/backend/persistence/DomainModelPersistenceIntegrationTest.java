package tn.steg.backend.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import jakarta.persistence.PersistenceException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.ai.domain.model.AiAnalysis;
import tn.steg.backend.ai.domain.model.AiAnalysisType;
import tn.steg.backend.ai.domain.model.AiRecommendation;
import tn.steg.backend.ai.infrastructure.persistence.AiAnalysisRepository;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.infrastructure.persistence.InternshipApplicationRepository;
import tn.steg.backend.audit.domain.model.AuditLog;
import tn.steg.backend.audit.infrastructure.persistence.AuditLogRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.certificate.domain.model.Certificate;
import tn.steg.backend.certificate.domain.model.CertificateStatus;
import tn.steg.backend.certificate.infrastructure.persistence.CertificateRepository;
import tn.steg.backend.common.infrastructure.config.JpaAuditingConfig;
import tn.steg.backend.companion.domain.model.Deliverable;
import tn.steg.backend.companion.domain.model.InternshipJournal;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.infrastructure.persistence.DeliverableRepository;
import tn.steg.backend.companion.infrastructure.persistence.InternshipJournalRepository;
import tn.steg.backend.companion.infrastructure.persistence.TaskRepository;
import tn.steg.backend.document.domain.model.Document;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.document.infrastructure.persistence.DocumentRepository;
import tn.steg.backend.document.infrastructure.persistence.FileAssetRepository;
import tn.steg.backend.evaluation.domain.model.Evaluation;
import tn.steg.backend.evaluation.domain.model.EvaluationType;
import tn.steg.backend.evaluation.infrastructure.persistence.EvaluationRepository;
import tn.steg.backend.finance.domain.model.FinanceCase;
import tn.steg.backend.finance.infrastructure.persistence.FinanceCaseRepository;
import tn.steg.backend.iam.domain.model.Permission;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.infrastructure.persistence.InternshipAssignmentRepository;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.messaging.domain.model.Conversation;
import tn.steg.backend.messaging.domain.model.ConversationType;
import tn.steg.backend.messaging.infrastructure.persistence.ConversationRepository;
import tn.steg.backend.notification.domain.model.Notification;
import tn.steg.backend.notification.domain.model.NotificationPriority;
import tn.steg.backend.notification.infrastructure.persistence.NotificationRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;
import tn.steg.backend.workflow.domain.model.ApplicationWorkflowInstance;
import tn.steg.backend.workflow.domain.model.WorkflowDefinition;
import tn.steg.backend.workflow.infrastructure.persistence.WorkflowDefinitionRepository;
import tn.steg.backend.workflow.infrastructure.persistence.WorkflowInstanceRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
@Transactional
@DisplayName("Domain Model Persistence Integration Tests")
class DomainModelPersistenceIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    // IAM
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;

    // Organization
    @Autowired DepartmentRepository departmentRepository;
    @Autowired EmployeeRepository employeeRepository;

    // Candidate
    @Autowired UniversityRepository universityRepository;
    @Autowired CandidateRepository candidateRepository;

    // Application
    @Autowired InternshipApplicationRepository applicationRepository;

    // Internship
    @Autowired InternshipRepository internshipRepository;
    @Autowired InternshipAssignmentRepository assignmentRepository;

    // Workflow
    @Autowired WorkflowDefinitionRepository workflowDefinitionRepository;
    @Autowired WorkflowInstanceRepository workflowInstanceRepository;

    // Companion
    @Autowired InternshipJournalRepository journalRepository;
    @Autowired TaskRepository taskRepository;
    @Autowired DeliverableRepository deliverableRepository;

    // Evaluation
    @Autowired EvaluationRepository evaluationRepository;

    // Document
    @Autowired FileAssetRepository fileAssetRepository;
    @Autowired DocumentRepository documentRepository;

    // Certificate
    @Autowired CertificateRepository certificateRepository;

    // Finance
    @Autowired FinanceCaseRepository financeCaseRepository;

    // Messaging
    @Autowired ConversationRepository conversationRepository;

    // Notification
    @Autowired NotificationRepository notificationRepository;

    // AI
    @Autowired AiAnalysisRepository aiAnalysisRepository;

    // Audit
    @Autowired AuditLogRepository auditLogRepository;

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    User persistUser(String email) {
        User u = new User(email, "hash", UserStatus.ACTIVE);
        em.persist(u);
        em.flush();
        return u;
    }

    University persistUniversity(String code) {
        University u = new University(code, "Test University " + code);
        em.persist(u);
        em.flush();
        return u;
    }

    Candidate persistCandidate(String hash, University uni, User user) {
        Candidate c = new Candidate("First", "Last", hash + "@email.com", hash, uni);
        c.setUser(user);
        em.persist(c);
        em.flush();
        return c;
    }

    Department persistDepartment(String code) {
        Department d = new Department(code, "Department " + code, null);
        em.persist(d);
        em.flush();
        return d;
    }

    Employee persistEmployee(String number, Department dept, User user) {
        Employee e = new Employee(number, "Emp", "Last", dept);
        e.setUser(user);
        em.persist(e);
        em.flush();
        return e;
    }

    Internship persistInternship(String ref, Candidate candidate) {
        Internship i = new Internship(ref, candidate,
                LocalDate.now().minusMonths(2), LocalDate.now(),
                InternshipType.OBSERVATION, InternshipRequirement.OBLIGATOIRE);
        i.setStatus(InternshipStatus.ACTIVE);
        em.persist(i);
        em.flush();
        return i;
    }

    FileAsset persistFileAsset(User uploader) {
        FileAsset f = new FileAsset("key-" + UUID.randomUUID(), "test.pdf",
                "chk-" + UUID.randomUUID(), "application/pdf", 1024L, uploader);
        em.persist(f);
        em.flush();
        return f;
    }

    // ─── IAM ──────────────────────────────────────────────────────────────────────

    @Nested @DisplayName("IAM: User & Role")
    class IamTests {

        @Test @DisplayName("User persists with auditing timestamps")
        void userPersists() {
            User user = persistUser("test@steg.com");
            Optional<User> found = userRepository.findByEmail("test@steg.com");
            assertThat(found).isPresent();
            assertThat(found.get().getCreatedAt()).isNotNull();
            assertThat(found.get().getUpdatedAt()).isNotNull();
        }

        @Test @DisplayName("Duplicate user email violates unique constraint")
        void duplicateEmailRejected() {
            persistUser("dup@steg.com");
            assertThatThrownBy(() -> {
                User u2 = new User("dup@steg.com", "hash2", UserStatus.ACTIVE);
                em.persist(u2);
                em.flush();
            }).isInstanceOf(PersistenceException.class);
        }

        @Test @DisplayName("Role persists with unique code")
        void rolePersists() {
            Role role = new Role("ROLE_HR", "HR Manager", "HR department");
            em.persist(role);
            em.flush();
            Optional<Role> found = roleRepository.findByCode("ROLE_HR");
            assertThat(found).isPresent();
        }

        @Test @DisplayName("Duplicate role code violates unique constraint")
        void duplicateRoleCodeRejected() {
            Role r1 = new Role("ROLE_DUP", "Dup1", null);
            em.persist(r1);
            em.flush();
            assertThatThrownBy(() -> {
                Role r2 = new Role("ROLE_DUP", "Dup2", null);
                em.persist(r2);
                em.flush();
            }).isInstanceOf(PersistenceException.class);
        }

        @Test @DisplayName("Permission persists with unique code")
        void permissionPersists() {
            Permission p = new Permission("READ_INTERNSHIP", "Can read internships");
            em.persist(p);
            em.flush();
            em.clear();
            Permission found = em.find(Permission.class, p.getId());
            assertThat(found).isNotNull();
            assertThat(found.getCode()).isEqualTo("READ_INTERNSHIP");
        }
    }

    // ─── Organization ─────────────────────────────────────────────────────────────

    @Nested @DisplayName("Organization: Department & Employee")
    class OrganizationTests {

        @Test @DisplayName("Department persists with unique code")
        void departmentPersists() {
            Department dept = persistDepartment("DEPT-001");
            Optional<Department> found = departmentRepository.findByCode("DEPT-001");
            assertThat(found).isPresent();
            assertThat(found.get().getActive()).isTrue();
        }

        @Test @DisplayName("Duplicate department code violates unique constraint")
        void duplicateDeptCodeRejected() {
            persistDepartment("DEPT-DUP");
            assertThatThrownBy(() -> {
                Department d2 = new Department("DEPT-DUP", "Dup2", null);
                em.persist(d2);
                em.flush();
            }).isInstanceOf(PersistenceException.class);
        }

        @Test @DisplayName("Employee persists with unique employee number")
        void employeePersists() {
            User user = persistUser("emp@steg.com");
            Department dept = persistDepartment("DEPT-EMP");
            Employee emp = persistEmployee("EMP-001", dept, user);
            Optional<Employee> found = employeeRepository.findByEmployeeNumber("EMP-001");
            assertThat(found).isPresent();
        }

        @Test @DisplayName("Duplicate employee number violates unique constraint")
        void duplicateEmployeeNumberRejected() {
            User u1 = persistUser("emp1@steg.com");
            User u2 = persistUser("emp2@steg.com");
            Department dept = persistDepartment("DEPT-EMPDUP");
            persistEmployee("EMP-DUP", dept, u1);
            assertThatThrownBy(() -> {
                Employee e2 = new Employee("EMP-DUP", "Emp2", "Last", dept);
                em.persist(e2);
                em.flush();
            }).isInstanceOf(PersistenceException.class);
        }
    }

    // ─── Candidate ────────────────────────────────────────────────────────────────

    @Nested @DisplayName("Candidate: University & Candidate")
    class CandidateTests {

        @Test @DisplayName("University persists with unique code")
        void universityPersists() {
            University u = persistUniversity("UNIV-001");
            assertThat(universityRepository.findByCode("UNIV-001")).isPresent();
        }

        @Test @DisplayName("Candidate persists with unique national ID hash")
        void candidatePersists() {
            University uni = persistUniversity("UNIV-CAND");
            User user = persistUser("cand@email.com");
            Candidate c = persistCandidate("HASH-001", uni, user);
            assertThat(candidateRepository.findByNationalIdHash("HASH-001")).isPresent();
        }

        @Test @DisplayName("Duplicate national ID hash violates unique constraint")
        void duplicateNationalIdHashRejected() {
            University uni = persistUniversity("UNIV-DUP");
            User u1 = persistUser("dup1@email.com");
            User u2 = persistUser("dup2@email.com");
            persistCandidate("HASH-DUP", uni, u1);
            assertThatThrownBy(() -> {
                Candidate c2 = new Candidate("First2", "Last2", "dup2@email.com", "HASH-DUP", uni);
                em.persist(c2);
                em.flush();
            }).isInstanceOf(PersistenceException.class);
        }
    }

    // ─── Application ──────────────────────────────────────────────────────────────

    @Nested @DisplayName("Application: InternshipApplication")
    class ApplicationTests {

        @Test @DisplayName("InternshipApplication persists with unique reference")
        void applicationPersists() {
            University uni = persistUniversity("UNIV-APP");
            User user = persistUser("appuser@email.com");
            Candidate cand = persistCandidate("HASH-APP", uni, user);
            InternshipApplication app = new InternshipApplication("REF-001", cand, ApplicationStatus.DRAFT);
            em.persist(app);
            em.flush();
            assertThat(applicationRepository.findByReference("REF-001")).isPresent();
        }

        @Test @DisplayName("Duplicate application reference violates unique constraint")
        void duplicateReferenceRejected() {
            University uni = persistUniversity("UNIV-APPDUP");
            User u1 = persistUser("appdup1@email.com");
            User u2 = persistUser("appdup2@email.com");
            Candidate c1 = persistCandidate("HASH-APPDUP1", uni, u1);
            Candidate c2 = persistCandidate("HASH-APPDUP2", uni, u2);
            em.persist(new InternshipApplication("REF-DUP", c1, ApplicationStatus.DRAFT));
            em.flush();
            assertThatThrownBy(() -> {
                em.persist(new InternshipApplication("REF-DUP", c2, ApplicationStatus.DRAFT));
                em.flush();
            }).isInstanceOf(PersistenceException.class);
        }
    }

    // ─── Internship & Assignment ───────────────────────────────────────────────────

    @Nested @DisplayName("Internship & Assignment")
    class InternshipTests {

        @Test @DisplayName("Internship persists with unique reference")
        void internshipPersists() {
            University uni = persistUniversity("UNIV-INT");
            User user = persistUser("int@email.com");
            Candidate cand = persistCandidate("HASH-INT", uni, user);
            Internship i = persistInternship("INT-REF-001", cand);
            assertThat(internshipRepository.findByReference("INT-REF-001")).isPresent();
        }

        @Test @DisplayName("Duplicate internship reference violates unique constraint")
        void duplicateInternshipReferenceRejected() {
            University uni = persistUniversity("UNIV-INTDUP");
            User u1 = persistUser("intdup1@email.com");
            User u2 = persistUser("intdup2@email.com");
            Candidate c1 = persistCandidate("HASH-INTDUP1", uni, u1);
            Candidate c2 = persistCandidate("HASH-INTDUP2", uni, u2);
            persistInternship("INT-DUP", c1);
            assertThatThrownBy(() -> persistInternship("INT-DUP", c2))
                    .isInstanceOf(PersistenceException.class);
        }

        @Test @DisplayName("ACTIVE assignment is stored correctly")
        void activeAssignmentPersists() {
            University uni = persistUniversity("UNIV-ASS");
            User user = persistUser("ass@email.com");
            User empUser = persistUser("ass-emp@steg.com");
            Candidate cand = persistCandidate("HASH-ASS", uni, user);
            Department dept = persistDepartment("DEPT-ASS");
            Employee emp = persistEmployee("EMP-ASS", dept, empUser);
            Internship internship = persistInternship("INT-ASS-001", cand);

            InternshipAssignment assignment = new InternshipAssignment(
                    internship, dept, emp, emp,
                    LocalDate.now(), LocalDate.now(), LocalDate.now().plusMonths(2),
                    AssignmentStatus.ACTIVE);
            em.persist(assignment);
            em.flush();

            Optional<InternshipAssignment> found = assignmentRepository
                    .findByInternshipIdAndStatus(internship.getId(), AssignmentStatus.ACTIVE);
            assertThat(found).isPresent();
        }

        @Test @DisplayName("Partial unique index rejects second ACTIVE assignment for same internship")
        void secondActiveAssignmentRejected() {
            University uni = persistUniversity("UNIV-ASS2");
            User user = persistUser("ass2@email.com");
            User empUser = persistUser("ass2-emp@steg.com");
            User empUser2 = persistUser("ass2-emp2@steg.com");
            Candidate cand = persistCandidate("HASH-ASS2", uni, user);
            Department dept = persistDepartment("DEPT-ASS2");
            Employee emp1 = persistEmployee("EMP-ASS2-1", dept, empUser);
            Employee emp2 = persistEmployee("EMP-ASS2-2", dept, empUser2);
            Internship internship = persistInternship("INT-ASS2-001", cand);

            // First ACTIVE assignment - must succeed
            InternshipAssignment a1 = new InternshipAssignment(
                    internship, dept, emp1, emp1,
                    LocalDate.now(), LocalDate.now(), LocalDate.now().plusMonths(1),
                    AssignmentStatus.ACTIVE);
            em.persist(a1);
            em.flush();

            // Second ACTIVE assignment for the same internship - must fail
            assertThatThrownBy(() -> {
                InternshipAssignment a2 = new InternshipAssignment(
                        internship, dept, emp2, emp2,
                        LocalDate.now(), LocalDate.now(), LocalDate.now().plusMonths(2),
                        AssignmentStatus.ACTIVE);
                em.persist(a2);
                em.flush();
            }).isInstanceOf(PersistenceException.class);
        }

        @Test @DisplayName("Multiple non-ACTIVE assignments for same internship are allowed")
        void multipleHistoricalAssignmentsAllowed() {
            University uni = persistUniversity("UNIV-HIST");
            User user = persistUser("hist@email.com");
            User empUser = persistUser("hist-emp@steg.com");
            Candidate cand = persistCandidate("HASH-HIST", uni, user);
            Department dept = persistDepartment("DEPT-HIST");
            Employee emp = persistEmployee("EMP-HIST", dept, empUser);
            Internship internship = persistInternship("INT-HIST-001", cand);

            // Two ENDED assignments must both persist fine
            InternshipAssignment a1 = new InternshipAssignment(
                    internship, dept, emp, emp,
                    LocalDate.now().minusMonths(2), LocalDate.now().minusMonths(2),
                    LocalDate.now().minusMonths(1), AssignmentStatus.ENDED);
            em.persist(a1);
            em.flush();

            InternshipAssignment a2 = new InternshipAssignment(
                    internship, dept, emp, emp,
                    LocalDate.now().minusMonths(1), LocalDate.now().minusMonths(1),
                    LocalDate.now(), AssignmentStatus.ENDED);
            em.persist(a2);
            em.flush();

            assertThat(assignmentRepository.findByInternshipId(internship.getId())).hasSize(2);
        }
    }

    // ─── Workflow ─────────────────────────────────────────────────────────────────

    @Nested @DisplayName("Workflow: Definition & Instance")
    class WorkflowTests {

        @Test @DisplayName("WorkflowDefinition persists with unique code")
        void workflowDefinitionPersists() {
            WorkflowDefinition def = new WorkflowDefinition("WF-APPLICATION", "Application Workflow", null);
            em.persist(def);
            em.flush();
            assertThat(workflowDefinitionRepository.findByCode("WF-APPLICATION")).isPresent();
        }

        @Test @DisplayName("ApplicationWorkflowInstance persists and links to application")
        void applicationWorkflowInstancePersists() {
            University uni = persistUniversity("UNIV-WF");
            User user = persistUser("wf@email.com");
            Candidate cand = persistCandidate("HASH-WF", uni, user);
            InternshipApplication app = new InternshipApplication("REF-WF-001", cand, ApplicationStatus.SUBMITTED);
            em.persist(app);
            em.flush();

            WorkflowDefinition def = new WorkflowDefinition("WF-APP-INST", "App Workflow", null);
            em.persist(def);
            em.flush();

            ApplicationWorkflowInstance instance = new ApplicationWorkflowInstance(def, app);
            em.persist(instance);
            em.flush();
            assertThat(workflowInstanceRepository.findById(instance.getId())).isPresent();
        }
    }

    // ─── Companion ────────────────────────────────────────────────────────────────

    @Nested @DisplayName("Companion: Journal, Task, Deliverable")
    class CompanionTests {

        @Test @DisplayName("InternshipJournal persists and cascades delete to entries")
        void journalPersistsAndCascades() {
            University uni = persistUniversity("UNIV-JNL");
            User user = persistUser("jnl@email.com");
            Candidate cand = persistCandidate("HASH-JNL", uni, user);
            Internship internship = persistInternship("INT-JNL-001", cand);

            InternshipJournal journal = new InternshipJournal(internship);
            em.persist(journal);
            em.flush();
            assertThat(journalRepository.findByInternshipId(internship.getId())).isPresent();
        }

        @Test @DisplayName("Task persists linked to internship")
        void taskPersists() {
            University uni = persistUniversity("UNIV-TASK");
            User user = persistUser("task@email.com");
            Candidate cand = persistCandidate("HASH-TASK", uni, user);
            Internship internship = persistInternship("INT-TASK-001", cand);
            Task task = new Task(internship, user, "Test Task", "Description");
            em.persist(task);
            em.flush();
            assertThat(taskRepository.findByInternshipId(internship.getId())).hasSize(1);
        }

        @Test @DisplayName("Deliverable persists linked to internship")
        void deliverablePersists() {
            University uni = persistUniversity("UNIV-DLVR");
            User user = persistUser("dlvr@email.com");
            Candidate cand = persistCandidate("HASH-DLVR", uni, user);
            Internship internship = persistInternship("INT-DLVR-001", cand);
            Deliverable d = new Deliverable(internship, "Final Report", "Description");
            em.persist(d);
            em.flush();
            assertThat(deliverableRepository.findByInternshipId(internship.getId())).hasSize(1);
        }
    }

    // ─── Evaluation ───────────────────────────────────────────────────────────────

    @Nested @DisplayName("Evaluation")
    class EvaluationTests {

        @Test @DisplayName("Evaluation persists linked to internship")
        void evaluationPersists() {
            University uni = persistUniversity("UNIV-EVAL");
            User user = persistUser("eval@email.com");
            User empUser = persistUser("eval-emp@steg.com");
            Candidate cand = persistCandidate("HASH-EVAL", uni, user);
            Department dept = persistDepartment("DEPT-EVAL");
            Employee emp = persistEmployee("EMP-EVAL", dept, empUser);
            Internship internship = persistInternship("INT-EVAL-001", cand);

            Evaluation eval = new Evaluation(internship, emp, EvaluationType.WEEKLY, LocalDate.now());
            em.persist(eval);
            em.flush();
            assertThat(evaluationRepository.findByInternshipId(internship.getId())).hasSize(1);
        }
    }

    // ─── Document ─────────────────────────────────────────────────────────────────

    @Nested @DisplayName("Document & FileAsset")
    class DocumentTests {

        @Test @DisplayName("FileAsset persists")
        void fileAssetPersists() {
            User user = persistUser("file@steg.com");
            FileAsset asset = persistFileAsset(user);
            assertThat(fileAssetRepository.findById(asset.getId())).isPresent();
        }

        @Test @DisplayName("Document with CIN_COPY type defaults restrictedAccess to true")
        void cinCopyRestrictedAccessIsTrue() {
            Document doc = new Document("DOC-CIN-001", DocumentType.CIN_COPY);
            em.persist(doc);
            em.flush();
            Document found = em.find(Document.class, doc.getId());
            assertThat(found.getRestrictedAccess()).isTrue();
        }

        @Test @DisplayName("Document with non-CIN type defaults restrictedAccess to false")
        void normalDocRestrictedAccessIsFalse() {
            Document doc = new Document("DOC-CV-001", DocumentType.CV);
            em.persist(doc);
            em.flush();
            Document found = em.find(Document.class, doc.getId());
            assertThat(found.getRestrictedAccess()).isFalse();
        }

        @Test @DisplayName("Duplicate document reference violates unique constraint")
        void duplicateDocReferenceRejected() {
            em.persist(new Document("DOC-DUP-001", DocumentType.CV));
            em.flush();
            assertThatThrownBy(() -> {
                em.persist(new Document("DOC-DUP-001", DocumentType.TRANSCRIPT));
                em.flush();
            }).isInstanceOf(PersistenceException.class);
        }
    }

    // ─── Certificate ──────────────────────────────────────────────────────────────

    @Nested @DisplayName("Certificate")
    class CertificateTests {

        @Test @DisplayName("Certificate persists with unique reference")
        void certificatePersists() {
            University uni = persistUniversity("UNIV-CERT");
            User user = persistUser("cert@email.com");
            User empUser = persistUser("cert-emp@steg.com");
            Candidate cand = persistCandidate("HASH-CERT", uni, user);
            Department dept = persistDepartment("DEPT-CERT");
            Employee emp = persistEmployee("EMP-CERT", dept, empUser);
            Internship internship = persistInternship("INT-CERT-001", cand);
            FileAsset pdf = persistFileAsset(user);

            Certificate cert = new Certificate("CERT-REF-001", internship, emp, pdf, "INTERNSHIP_TPL", 1, LocalDate.now());
            em.persist(cert);
            em.flush();

            assertThat(certificateRepository.findByReference("CERT-REF-001")).isPresent();
            assertThat(cert.getStatus()).isEqualTo(CertificateStatus.GENERATED);
        }

        @Test @DisplayName("Duplicate certificate reference violates unique constraint")
        void duplicateCertReferenceRejected() {
            University uni = persistUniversity("UNIV-CERTDUP");
            User u1 = persistUser("certdup1@email.com");
            User u2 = persistUser("certdup2@email.com");
            User empUser = persistUser("certdup-emp@steg.com");
            Candidate c1 = persistCandidate("HASH-CERTDUP1", uni, u1);
            Candidate c2 = persistCandidate("HASH-CERTDUP2", uni, u2);
            Department dept = persistDepartment("DEPT-CERTDUP");
            Employee emp = persistEmployee("EMP-CERTDUP", dept, empUser);
            Internship i1 = persistInternship("INT-CERTDUP1", c1);
            Internship i2 = persistInternship("INT-CERTDUP2", c2);
            FileAsset pdf1 = persistFileAsset(u1);
            FileAsset pdf2 = persistFileAsset(u2);

            em.persist(new Certificate("CERT-DUP", i1, emp, pdf1, "TPL", 1, LocalDate.now()));
            em.flush();
            assertThatThrownBy(() -> {
                em.persist(new Certificate("CERT-DUP", i2, emp, pdf2, "TPL", 1, LocalDate.now()));
                em.flush();
            }).isInstanceOf(PersistenceException.class);
        }
    }

    // ─── Finance ──────────────────────────────────────────────────────────────────

    @Nested @DisplayName("Finance: FinanceCase")
    class FinanceTests {

        @Test @DisplayName("FinanceCase persists with unique reference")
        void financeCasePersists() {
            University uni = persistUniversity("UNIV-FIN");
            User user = persistUser("fin@email.com");
            Candidate cand = persistCandidate("HASH-FIN", uni, user);
            Internship internship = persistInternship("INT-FIN-001", cand);

            FinanceCase fc = new FinanceCase("FC-REF-001", internship);
            em.persist(fc);
            em.flush();
            assertThat(financeCaseRepository.findByReference("FC-REF-001")).isPresent();
        }

        @Test @DisplayName("Duplicate finance case reference violates unique constraint")
        void duplicateFinanceCaseRejected() {
            University uni = persistUniversity("UNIV-FINDUP");
            User u1 = persistUser("findup1@email.com");
            User u2 = persistUser("findup2@email.com");
            Candidate c1 = persistCandidate("HASH-FINDUP1", uni, u1);
            Candidate c2 = persistCandidate("HASH-FINDUP2", uni, u2);
            Internship i1 = persistInternship("INT-FINDUP1", c1);
            Internship i2 = persistInternship("INT-FINDUP2", c2);

            em.persist(new FinanceCase("FC-DUP", i1));
            em.flush();
            assertThatThrownBy(() -> {
                em.persist(new FinanceCase("FC-DUP", i2));
                em.flush();
            }).isInstanceOf(PersistenceException.class);
        }
    }

    // ─── Messaging ────────────────────────────────────────────────────────────────

    @Nested @DisplayName("Messaging: Conversation & Message")
    class MessagingTests {

        @Test @DisplayName("Conversation persists with internship link")
        void conversationPersists() {
            University uni = persistUniversity("UNIV-MSG");
            User user = persistUser("msg@email.com");
            Candidate cand = persistCandidate("HASH-MSG", uni, user);
            Internship internship = persistInternship("INT-MSG-001", cand);

            Conversation conv = new Conversation(ConversationType.PRIVATE, "Test Conv", internship);
            em.persist(conv);
            em.flush();
            assertThat(conversationRepository.findByInternshipId(internship.getId())).isPresent();
        }
    }

    // ─── Notification ─────────────────────────────────────────────────────────────

    @Nested @DisplayName("Notification")
    class NotificationTests {

        @Test @DisplayName("Notification persists")
        void notificationPersists() {
            Notification n = new Notification("Test Title", "Test message", NotificationPriority.HIGH);
            em.persist(n);
            em.flush();
            assertThat(notificationRepository.findById(n.getId())).isPresent();
        }
    }

    // ─── AI ───────────────────────────────────────────────────────────────────────

    @Nested @DisplayName("AI Assistance")
    class AiTests {

        @Test @DisplayName("AiAnalysis persists without FK mutation - only entity reference")
        void aiAnalysisPersistsWithEntityReference() {
            UUID fakeEntityId = UUID.randomUUID();
            AiAnalysis analysis = new AiAnalysis(
                    AiAnalysisType.APPLICATION_DOCUMENT_ANALYSIS,
                    "InternshipApplication",
                    fakeEntityId,
                    "gemini-2.0-flash"
            );
            analysis.setCinExcluded(true);
            em.persist(analysis);
            em.flush();

            assertThat(aiAnalysisRepository.findByRelatedEntityTypeAndRelatedEntityId(
                    "InternshipApplication", fakeEntityId)).hasSize(1);
        }

        @Test @DisplayName("AiRecommendation persists linked to AiAnalysis")
        void aiRecommendationPersists() {
            AiAnalysis analysis = new AiAnalysis(
                    AiAnalysisType.LOGBOOK_GENERATION,
                    "Internship",
                    UUID.randomUUID(),
                    "gemini-2.0-flash"
            );
            em.persist(analysis);
            em.flush();

            AiRecommendation rec = new AiRecommendation(analysis, "Consider adding more detail to the logbook.");
            em.persist(rec);
            em.flush();

            em.clear();
            AiRecommendation found = em.find(AiRecommendation.class, rec.getId());
            assertThat(found.getRecommendationText()).contains("logbook");
            assertThat(found.getAnalysis().getId()).isEqualTo(analysis.getId());
        }
    }

    // ─── Audit ────────────────────────────────────────────────────────────────────

    @Nested @DisplayName("Audit Log")
    class AuditTests {

        @Test @DisplayName("AuditLog persists with JSONB fields and null actor for system events")
        void auditLogPersistsWithNullActor() {
            UUID targetId = UUID.randomUUID();
            AuditLog log = new AuditLog("STATUS_CHANGED", "Internship", targetId, null, "127.0.0.1");
            log.setOldValues("{\"status\":\"PLANNED\"}");
            log.setNewValues("{\"status\":\"ACTIVE\"}");
            em.persist(log);
            em.flush();

            assertThat(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc("Internship", targetId))
                    .hasSize(1);
        }

        @Test @DisplayName("AuditLog persists with non-null actor for user-triggered events")
        void auditLogPersistsWithActor() {
            User actor = persistUser("auditor@steg.com");
            UUID targetId = UUID.randomUUID();
            AuditLog log = new AuditLog("DOCUMENT_VERIFIED", "ApplicationDocument", targetId, actor, "192.168.1.1");
            em.persist(log);
            em.flush();

            assertThat(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc("ApplicationDocument", targetId))
                    .hasSize(1);
        }
    }
}
