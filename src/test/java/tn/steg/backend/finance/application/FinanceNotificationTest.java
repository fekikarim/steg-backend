package tn.steg.backend.finance.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.certificate.application.CertificateService;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.document.application.DocumentService;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.finance.application.dto.AttachFinanceDocumentRequest;
import tn.steg.backend.finance.application.dto.PaymentDecisionRequest;
import tn.steg.backend.finance.application.dto.ReviewFinanceDocumentRequest;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;
import tn.steg.backend.workflow.application.WorkflowService;
import tn.steg.backend.workflow.application.dto.WorkflowTransitionRequest;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Notification fan-out for finance/certificate facts (Phase A11 hardening).
 *
 * <p>Deliberately NOT {@code @Transactional}: BEFORE_COMMIT listeners only
 * fire on real commits, exactly like production — proving rolled-back business
 * transactions emit no notifications and successful ones emit exactly one.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Finance Notification Fan-out Tests (A11 hardening)")
class FinanceNotificationTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 finance dossier file content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG_BYTES =
            new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateRepository candidateRepository;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private InternshipRepository internshipRepository;

    @Autowired
    private InternshipService internshipService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FinanceService financeService;

    @Autowired
    private CertificateService certificateService;

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;
    private UserPrincipal financePrincipal;
    private UserPrincipal supervisorPrincipal;
    private String supervisorToken;
    private String internToken;
    private UUID internshipId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User hrUser = userRepository.saveAndFlush(new User("hr_fn_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        User financeUser = userRepository.saveAndFlush(new User("fin_fn_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        User supervisorUser = userRepository.saveAndFlush(new User("sup_fn_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        User internUser = userRepository.saveAndFlush(new User("int_fn_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));

        financePrincipal = new UserPrincipal(financeUser.getId(), financeUser.getEmail(), List.of("ROLE_FINANCE"));
        supervisorPrincipal = new UserPrincipal(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));
        UserPrincipal hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));
        supervisorToken = jwtService.generateAccessToken(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(), List.of("ROLE_CANDIDATE"));

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_FN_" + suffix, "Fn Dept", "FN"));
        Employee supervisorEmployee = new Employee("EMP-FN-S-" + suffix, "Fn", "Sup", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);
        Employee financeEmployee = new Employee("EMP-FN-F-" + suffix, "Fn", "Fin", dept);
        financeEmployee.setUser(financeUser);
        financeEmployee = employeeRepository.saveAndFlush(financeEmployee);

        University uni = universityRepository.saveAndFlush(new University("UNI_FN_" + suffix, "Fn Uni"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("FN" + suffix).getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Fn", "Intern", "int_fn_" + suffix + "@steg.com", cinHash, uni);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("FN" + suffix);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "Fn project", "Ingénieur", false), hrPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();
        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), "fn"), hrPrincipal);
        workflowService.transitionInternship(internship.getId(),
                new WorkflowTransitionRequest("COMPLETED", WorkflowActionType.COMPLETION, null, "done"), hrPrincipal);
        internshipId = internship.getId();
    }

    private UUID readyCase() {
        UUID caseId = financeService.openFinanceCase(internshipId, financePrincipal).id();
        attach("cin.png", "image/png", PNG_BYTES, DocumentType.CIN_COPY, caseId);
        attach("app.pdf", "application/pdf", PDF_BYTES, DocumentType.INTERNSHIP_APPLICATION, caseId);
        attach("assign.pdf", "application/pdf", PDF_BYTES, DocumentType.ASSIGNMENT_LETTER, caseId);
        attach("report.pdf", "application/pdf", PDF_BYTES, DocumentType.STEG_INTERNSHIP_REPORT, caseId);
        return caseId;
    }

    private void attach(String name, String contentType, byte[] bytes, DocumentType type, UUID caseId) {
        MultipartFile file = new MockMultipartFile("file", name, contentType, bytes);
        UUID docId = documentService.uploadDocument(file, type, supervisorPrincipal).id();
        financeService.attachDocument(caseId, new AttachFinanceDocumentRequest(docId, true), financePrincipal);
        financeService.reviewDocument(caseId, docId,
                new ReviewFinanceDocumentRequest(
                        tn.steg.backend.document.domain.model.DocumentVerificationStatus.VERIFIED, null),
                financePrincipal);
    }

    private long countWithTitle(String token, String title) throws Exception {
        MvcResult list = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        long count = 0;
        for (JsonNode node : objectMapper.readTree(list.getResponse().getContentAsString()).get("content")) {
            if (title.equals(node.get("title").asText())) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("Approval publishes exactly one supervisor notification")
    void approvalNotifiesOnce() throws Exception {
        UUID caseId = readyCase();
        financeService.approve(caseId, new PaymentDecisionRequest("ok"), financePrincipal);
        assertThat(countWithTitle(supervisorToken, "Payment approved")).isEqualTo(1);
    }

    @Test
    @DisplayName("Failed approval publishes no notification (rolled-back facts stay silent)")
    void failedApprovalPublishesNothing() throws Exception {
        UUID caseId = financeService.openFinanceCase(internshipId, financePrincipal).id();
        assertThatThrownBy(() -> financeService.approve(caseId, new PaymentDecisionRequest("ok"), financePrincipal))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(countWithTitle(supervisorToken, "Payment approved")).isEqualTo(0);
    }

    @Test
    @DisplayName("Certificate generation publishes exactly one intern notification")
    void certificateNotifiesOnce() throws Exception {
        certificateService.generateCertificate(internshipId, supervisorPrincipal);
        assertThat(countWithTitle(internToken, "Certificate available")).isEqualTo(1);
    }
}
