package tn.steg.backend.finance.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.audit.infrastructure.persistence.AuditLogRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.document.domain.service.FileStorageService;
import tn.steg.backend.document.infrastructure.storage.LocalFileStorageService;
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
import tn.steg.backend.notification.infrastructure.persistence.NotificationRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;
import tn.steg.backend.workflow.application.WorkflowService;
import tn.steg.backend.workflow.application.dto.WorkflowTransitionRequest;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("Finance & Certificate Integration Tests (A11)")
class FinanceCertificateIntegrationTest {

    /**
     * Flag-controlled storage outage rig: delegates to the real local storage
     * unless armed, proving generation atomicity without disturbing other tests.
     */
    static class OutageStorage implements FileStorageService {
        final AtomicBoolean outage = new AtomicBoolean(false);
        private final FileStorageService delegate;

        OutageStorage(LocalFileStorageService delegate) {
            this.delegate = delegate;
        }

        @Override
        public String store(InputStream inputStream, String originalFilename, String mimeType) {
            if (outage.get()) {
                throw new RuntimeException("Simulated storage outage");
            }
            return delegate.store(inputStream, originalFilename, mimeType);
        }

        @Override
        public InputStream getInputStream(String storageKey) {
            return delegate.getInputStream(storageKey);
        }

        @Override
        public void delete(String storageKey) {
            delegate.delete(storageKey);
        }
    }

    @TestConfiguration
    static class OutageStorageConfiguration {
        @Bean
        @Primary
        FileStorageService outageStorage(LocalFileStorageService realStorage) {
            return new OutageStorage(realStorage);
        }
    }

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
    private tn.steg.backend.finance.domain.repository.PaymentCalculationRepository calculationRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private OutageStorage outageStorage;

    @Autowired
    private tn.steg.backend.document.infrastructure.persistence.FileAssetRepository fileAssetRepository;

    @Autowired
    private tn.steg.backend.document.infrastructure.persistence.DocumentRepository documentRepository;

    @Autowired
    private tn.steg.backend.certificate.infrastructure.persistence.CertificateRepository certificateRepository;

    @Autowired
    private tn.steg.backend.finance.infrastructure.persistence.PaymentReceiptRepository receiptRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private MockMvc mockMvc;

    private User hrUser;
    private User adminUser;
    private User financeUser;
    private User supervisorUser;
    private User internUser;
    private User outsiderUser;

    private String hrToken;
    private String adminToken;
    private String financeToken;
    private String supervisorToken;
    private String internToken;
    private String outsiderToken;

    private Department dept;
    private Employee supervisorEmployee;
    private Candidate internCandidate;
    private Internship obligatoryCompleted;
    private Internship optionalCompleted;
    private Internship activeOnly;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        hrUser = userRepository.saveAndFlush(new User("hr_fin@steg.com", "hash", UserStatus.ACTIVE));
        hrToken = jwtService.generateAccessToken(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));

        adminUser = userRepository.saveAndFlush(new User("admin_fin@steg.com", "hash", UserStatus.ACTIVE));
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        financeUser = userRepository.saveAndFlush(new User("finance_fin@steg.com", "hash", UserStatus.ACTIVE));
        financeToken = jwtService.generateAccessToken(financeUser.getId(), financeUser.getEmail(), List.of("ROLE_FINANCE"));

        supervisorUser = userRepository.saveAndFlush(new User("supervisor_fin@steg.com", "hash", UserStatus.ACTIVE));
        supervisorToken = jwtService.generateAccessToken(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        internUser = userRepository.saveAndFlush(new User("intern_fin@steg.com", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(), List.of("ROLE_CANDIDATE"));

        outsiderUser = userRepository.saveAndFlush(new User("outsider_fin@steg.com", "hash", UserStatus.ACTIVE));
        outsiderToken = jwtService.generateAccessToken(outsiderUser.getId(), outsiderUser.getEmail(), List.of("ROLE_CANDIDATE"));

        dept = departmentRepository.saveAndFlush(new Department("DIR_FIN", "Direction Financière", "FIN"));

        supervisorEmployee = new Employee("EMP-FIN-SUP", "Leila", "Ben Ammar", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        Employee financeEmployee = new Employee("EMP-FIN-1", "Karim", "Trabelsi", dept);
        financeEmployee.setUser(financeUser);
        employeeRepository.saveAndFlush(financeEmployee);

        University uni = universityRepository.saveAndFlush(new University("INSAT_FIN", "INSAT Tunis"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest("44556677".getBytes(StandardCharsets.UTF_8)));
        internCandidate = new Candidate("Sami", "Gharbi", "intern_fin@steg.com", cinHash, uni);
        internCandidate.setUser(internUser);
        internCandidate.setNationalIdEncrypted("44556677");
        internCandidate = candidateRepository.saveAndFlush(internCandidate);

        UserPrincipal hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));

        // OBLIGATOIRE 3-month internship, completed (Jan 1 → Apr 1 = exactly 3 months)
        obligatoryCompleted = createCompletedInternship(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), false, hrPrincipal);
        // OPTIONAL observation internship, completed (30 days)
        optionalCompleted = createCompletedInternship(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), false, hrPrincipal);
        // OBLIGATOIRE but still ACTIVE (not completed)
        activeOnly = createActiveInternship(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), hrPrincipal);
    }

    private Internship createCompletedInternship(LocalDate start, LocalDate end, boolean obligatory,
                                                 UserPrincipal hrPrincipal) {
        Internship internship = createActiveInternshipWithFlag(start, end, obligatory, hrPrincipal);
        workflowService.transitionInternship(internship.getId(),
                new WorkflowTransitionRequest("COMPLETED", WorkflowActionType.COMPLETION, null, "Internship completed"),
                hrPrincipal);
        return ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(internship.getId()).orElseThrow();
    }

    private Internship createActiveInternship(LocalDate start, LocalDate end, UserPrincipal hrPrincipal) {
        return createActiveInternshipWithFlag(start, end, false, hrPrincipal);
    }

    private Internship createActiveInternshipWithFlag(LocalDate start, LocalDate end, boolean obligatory,
                                                      UserPrincipal hrPrincipal) {
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                internCandidate.getId(), start, end, "STEG Finance PFE Project", "Ingénieur", obligatory), hrPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();
        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(), start, end, "Finance test assignment"), hrPrincipal);
        return ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();
    }

    private UUID uploadDocument(String token, byte[] bytes, String fileName, String contentType, String docType)
            throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", fileName, contentType, bytes))
                        .param("type", docType)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID openCase(UUID internshipId, String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/finance-cases")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internshipId\":\"" + internshipId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID attachDossier(UUID caseId) throws Exception {
        UUID cin = uploadDocument(supervisorToken, PNG_BYTES, "cin.png", "image/png", "CIN_COPY");
        UUID application = uploadDocument(supervisorToken, PDF_BYTES, "application.pdf", "application/pdf", "INTERNSHIP_APPLICATION");
        UUID assignment = uploadDocument(supervisorToken, PDF_BYTES, "assignment.pdf", "application/pdf", "ASSIGNMENT_LETTER");
        UUID report = uploadDocument(supervisorToken, PDF_BYTES, "report.pdf", "application/pdf", "STEG_INTERNSHIP_REPORT");
        for (UUID docId : List.of(cin, application, assignment, report)) {
            mockMvc.perform(post("/api/finance-cases/" + caseId + "/documents")
                            .header("Authorization", "Bearer " + financeToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"documentId\":\"" + docId + "\",\"mandatory\":true}"))
                    .andExpect(status().isCreated());
            mockMvc.perform(patch("/api/finance-cases/" + caseId + "/documents/" + docId)
                            .header("Authorization", "Bearer " + financeToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"VERIFIED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"));
        }
        return caseId;
    }

    private String caseStatus(UUID caseId, String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/finance-cases/" + caseId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("status").asText();
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private long countAudits(String entityType, String action) {
        return auditLogRepository.findAll().stream()
                .filter(a -> entityType.equals(a.getEntityType()) && action.equals(a.getAction()))
                .count();
    }

    // -------------------------------------------------------------------------
    // Branding resource
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Official STEG logo is present on the classpath and is a valid image")
    void officialLogoPresentAndValid() throws Exception {
        ClassPathResource logo = new ClassPathResource("assets/logo/logo-steg-1200x327.png");
        assertThat(logo.exists()).isTrue();
        try (var in = logo.getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            assertThat(image).isNotNull();
            assertThat(image.getWidth()).isEqualTo(1200);
            assertThat(image.getHeight()).isEqualTo(327);
        }
    }

    // -------------------------------------------------------------------------
    // Case opening guards
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Opening a case for an OBLIGATOIRE COMPLETED internship computes the exact snapshot")
    void openHappyPathComputesSnapshot() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/finance-cases")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internshipId\":\"" + obligatoryCompleted.getId() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").isNotEmpty())
                .andExpect(jsonPath("$.status").value("OPENED"))
                .andExpect(jsonPath("$.calculation.completedMonths").value(3))
                .andExpect(jsonPath("$.calculation.payableMonths").value(3))
                .andExpect(jsonPath("$.calculation.ratePerMonth").value(50.00))
                .andExpect(jsonPath("$.calculation.cappedAmount").value(150.00))
                .andExpect(jsonPath("$.calculation.currencyCode").value("TND"))
                .andExpect(jsonPath("$.workflowInstanceId").isNotEmpty())
                .andReturn();

        String reference = objectMapper.readTree(result.getResponse().getContentAsString()).get("reference").asText();
        assertThat(reference).startsWith("FC-");

        mockMvc.perform(get("/api/finance-cases")
                        .header("Authorization", "Bearer " + financeToken)
                        .param("status", "OPENED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reference").value(reference));

        mockMvc.perform(get("/api/finance-cases")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Cases cannot be opened for OPTIONAL internships")
    void openOptionalRejected() throws Exception {
        MvcResult classification = mockMvc.perform(
                        get("/api/internships/" + optionalCompleted.getId() + "/classification")
                                .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(classification.getResponse().getContentAsString())
                .get("requirement").asText()).isEqualTo("OPTIONAL");

        mockMvc.perform(post("/api/finance-cases")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internshipId\":\"" + optionalCompleted.getId() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INTERNSHIP_NOT_PAYABLE"));
    }

    @Test
    @DisplayName("Cases cannot be opened before COMPLETED, duplicated, or for unknown internships")
    void openGuards() throws Exception {
        // Still ACTIVE
        mockMvc.perform(post("/api/finance-cases")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internshipId\":\"" + activeOnly.getId() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INTERNSHIP_NOT_COMPLETED"));

        // Unknown internship
        mockMvc.perform(post("/api/finance-cases")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internshipId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());

        // Duplicate
        openCase(obligatoryCompleted.getId(), financeToken);
        mockMvc.perform(post("/api/finance-cases")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internshipId\":\"" + obligatoryCompleted.getId() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FINANCE_CASE_ALREADY_EXISTS"));

        // Candidate role cannot open
        mockMvc.perform(post("/api/finance-cases")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internshipId\":\"" + obligatoryCompleted.getId() + "\"}"))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Dossier → READY_FOR_DECISION
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Complete verified dossier advances the case to READY_FOR_DECISION")
    void dossierCompletionAdvancesCase() throws Exception {
        UUID caseId = openCase(obligatoryCompleted.getId(), financeToken);
        assertThat(caseStatus(caseId, financeToken)).isEqualTo("OPENED");

        UUID cin = uploadDocument(supervisorToken, PNG_BYTES, "cin.png", "image/png", "CIN_COPY");
        UUID application = uploadDocument(supervisorToken, PDF_BYTES, "application.pdf", "application/pdf", "INTERNSHIP_APPLICATION");

        // Partial dossier: attached but only half verified → not ready
        for (UUID docId : List.of(cin, application)) {
            mockMvc.perform(post("/api/finance-cases/" + caseId + "/documents")
                            .header("Authorization", "Bearer " + financeToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"documentId\":\"" + docId + "\",\"mandatory\":true}"))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(patch("/api/finance-cases/" + caseId + "/documents/" + cin)
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"VERIFIED\"}"))
                .andExpect(status().isOk());
        assertThat(caseStatus(caseId, financeToken)).isNotEqualTo("READY_FOR_DECISION");

        // Complete the dossier → READY_FOR_DECISION
        UUID assignment = uploadDocument(supervisorToken, PDF_BYTES, "assignment.pdf", "application/pdf", "ASSIGNMENT_LETTER");
        UUID report = uploadDocument(supervisorToken, PDF_BYTES, "report.pdf", "application/pdf", "STEG_INTERNSHIP_REPORT");
        for (UUID docId : List.of(application, assignment, report)) {
            mockMvc.perform(post("/api/finance-cases/" + caseId + "/documents")
                            .header("Authorization", "Bearer " + financeToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"documentId\":\"" + docId + "\",\"mandatory\":true}"))
                    .andExpect(status().isCreated());
            mockMvc.perform(patch("/api/finance-cases/" + caseId + "/documents/" + docId)
                            .header("Authorization", "Bearer " + financeToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"VERIFIED\"}"))
                    .andExpect(status().isOk());
        }
        assertThat(caseStatus(caseId, financeToken)).isEqualTo("READY_FOR_DECISION");

        // Optional demo image never blocks readiness
        UUID demo = uploadDocument(supervisorToken, PNG_BYTES, "demo.png", "image/png", "PROJECT_DEMO_IMAGE");
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/documents")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + demo + "\",\"mandatory\":false}"))
                .andExpect(status().isCreated());
        assertThat(caseStatus(caseId, financeToken)).isEqualTo("READY_FOR_DECISION");
    }

    // -------------------------------------------------------------------------
    // Decisions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Approval issues a distinct receipt PDF and notifies; non-FINANCE cannot decide")
    void approveHappyPath() throws Exception {
        UUID caseId = attachDossier(openCase(obligatoryCompleted.getId(), financeToken));

        // HR and ADMIN hold no FINANCE decision power
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        MvcResult approved = mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Dossier complete, payment cleared.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.receiptReference").isNotEmpty())
                .andExpect(jsonPath("$.approvals.length()").value(1))
                .andExpect(jsonPath("$.approvals[0].decision").value("APPROVED"))
                .andReturn();
        String receiptRef = objectMapper.readTree(approved.getResponse().getContentAsString())
                .get("receiptReference").asText();
        assertThat(receiptRef).startsWith("PAY-");

        // Receipt downloads as a valid PDF with the approved figures
        MvcResult receipt = mockMvc.perform(get("/api/finance-cases/" + caseId + "/receipt")
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isOk())
                .andReturn();
        byte[] receiptBytes = receipt.getResponse().getContentAsByteArray();
        assertThat(new String(receiptBytes, 0, 5, StandardCharsets.UTF_8)).isEqualTo("%PDF-");
        String receiptText = pdfText(receiptBytes);
        assertThat(receiptText).contains("Sami Gharbi");
        assertThat(receiptText).contains(receiptRef);
        assertThat(receiptText).contains("150.00");

        // Supervisor (notified party) can download; outsider cannot
        mockMvc.perform(get("/api/finance-cases/" + caseId + "/receipt")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/finance-cases/" + caseId + "/receipt")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        // Decided cases are immutable
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/recalculate")
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FINANCE_CASE_DECIDED"));
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Approval requires READY_FOR_DECISION; rejection requires a reason and issues no receipt")
    void approvalReadinessAndRejection() throws Exception {
        UUID caseId = openCase(obligatoryCompleted.getId(), financeToken);

        // Not ready yet → approve rejected
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FINANCE_CASE_NOT_READY"));

        attachDossier(caseId);

        // Reject without reason → 422; with reason → REJECTED, no receipt
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/reject")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("REJECTION_REASON_REQUIRED"));

        mockMvc.perform(post("/api/finance-cases/" + caseId + "/reject")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Amounts inconsistent with convention.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.receiptReference").doesNotExist());

        mockMvc.perform(get("/api/finance-cases/" + caseId + "/receipt")
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Recalculation pre-decision appends a new snapshot, preserving history")
    void recalculatePreDecision() throws Exception {
        UUID caseId = openCase(obligatoryCompleted.getId(), financeToken);
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/recalculate")
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPENED"))
                .andExpect(jsonPath("$.calculation.completedMonths").value(3))
                .andExpect(jsonPath("$.calculation.cappedAmount").value(150.00));

        // Immutable snapshots: two versioned rows, current is sequence 2
        var history = calculationRepository.findAllByFinanceCaseIdOrderByCalculationSequenceAsc(caseId);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getCalculationSequence()).isEqualTo(1);
        assertThat(history.get(1).getCalculationSequence()).isEqualTo(2);
        assertThat(history.get(1).getCappedAmount()).isEqualByComparingTo("150.00");
    }

    // -------------------------------------------------------------------------
    // Certificates
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Active supervisor generates a valid certificate PDF; client dates are impossible")
    void certificateGeneration() throws Exception {
        // A spoofed client date cannot even be expressed: the endpoint takes no
        // body, so any injected JSON is ignored and server time still rules.
        MvcResult generated = mockMvc.perform(post("/api/internships/" + obligatoryCompleted.getId() + "/certificates")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"generationDate\":\"2000-01-01T00:00:00Z\",\"issueDate\":\"2000-01-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").isNotEmpty())
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.issueDate").value(LocalDate.now().toString()))
                .andReturn();
        JsonNode cert = objectMapper.readTree(generated.getResponse().getContentAsString());
        assertThat(cert.get("reference").asText()).startsWith("CERT-");
        UUID certificateId = UUID.fromString(cert.get("id").asText());

        // Repeated generation is explicitly rejected (no silent duplicates).
        mockMvc.perform(post("/api/internships/" + obligatoryCompleted.getId() + "/certificates")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CERTIFICATE_ALREADY_EXISTS"));

        // Intern (non-supervisor) and non-COMPLETED internships are rejected
        mockMvc.perform(post("/api/internships/" + obligatoryCompleted.getId() + "/certificates")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/internships/" + activeOnly.getId() + "/certificates")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INTERNSHIP_NOT_COMPLETED"));

        // Download is a valid PDF mentioning the intern and the reference
        MvcResult download = mockMvc.perform(get("/api/certificates/" + certificateId)
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andReturn();
        byte[] pdf = download.getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.UTF_8)).isEqualTo("%PDF-");
        String text = pdfText(pdf);
        assertThat(text).contains("Sami Gharbi");
        assertThat(text).contains(cert.get("reference").asText());

        // The intern owner can download their own certificate; outsiders cannot
        mockMvc.perform(get("/api/certificates/" + certificateId)
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/certificates/" + certificateId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Certificate and payment receipt are distinct artifacts")
    void certificateAndReceiptAreDistinct() throws Exception {
        MvcResult certResult = mockMvc.perform(post("/api/internships/" + obligatoryCompleted.getId() + "/certificates")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isCreated())
                .andReturn();
        String certRef = objectMapper.readTree(certResult.getResponse().getContentAsString())
                .get("reference").asText();

        UUID caseId = attachDossier(openCase(obligatoryCompleted.getId(), financeToken));
        MvcResult approved = mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        String receiptRef = objectMapper.readTree(approved.getResponse().getContentAsString())
                .get("receiptReference").asText();

        assertThat(certRef).startsWith("CERT-");
        assertThat(receiptRef).startsWith("PAY-");
        assertThat(certRef).isNotEqualTo(receiptRef);

        byte[] certBytes = mockMvc.perform(get("/api/certificates/"
                        + objectMapper.readTree(certResult.getResponse().getContentAsString()).get("id").asText())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        byte[] receiptBytes = mockMvc.perform(get("/api/finance-cases/" + caseId + "/receipt")
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(certBytes).isNotEqualTo(receiptBytes);
        assertThat(pdfText(certBytes)).contains("ATTESTATION");
        assertThat(pdfText(receiptBytes)).contains("REÇU");
    }

    @Test
    @DisplayName("Official templates carry titles, tables, identity data and the validation TODO")
    void officialTemplateContent() throws Exception {
        MvcResult certResult = mockMvc.perform(post("/api/internships/" + obligatoryCompleted.getId() + "/certificates")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isCreated())
                .andReturn();
        String certText = pdfText(mockMvc.perform(get("/api/certificates/"
                        + objectMapper.readTree(certResult.getResponse().getContentAsString()).get("id").asText())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());

        assertThat(certText).contains("ATTESTATION DE STAGE");
        assertThat(certText).contains("Nom et prénom");
        assertThat(certText).contains("Sami Gharbi");
        assertThat(certText).contains("44556677");
        assertThat(certText).contains("INSAT Tunis");
        assertThat(certText).contains("Fait à Tunis");
        assertThat(certText).contains("TODO — VALIDATION");
        assertThat(certText).contains("OFFICIELLE STEG REQUISE");
        assertThat(certText).doesNotContain("150.00");

        UUID caseId = attachDossier(openCase(obligatoryCompleted.getId(), financeToken));
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        String receiptText = pdfText(mockMvc.perform(get("/api/finance-cases/" + caseId + "/receipt")
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());

        assertThat(receiptText).contains("REÇU DE PAIEMENT");
        assertThat(receiptText).contains("Bénéficiaire");
        assertThat(receiptText).contains("Sami Gharbi");
        assertThat(receiptText).contains("44556677");
        assertThat(receiptText).contains("150.00");
        assertThat(receiptText).contains("TODO — VALIDATION");
        assertThat(receiptText).contains("OFFICIELLE STEG REQUISE");
        assertThat(receiptText).doesNotContain("ATTESTATION");
    }

    @Test
    @DisplayName("Storage outage fails generation atomically: no orphan rows, no false completion, retry is safe")
    void storageOutageIsAtomic() throws Exception {
        UUID caseId = attachDossier(openCase(obligatoryCompleted.getId(), financeToken));
        long assetsBefore = fileAssetRepository.count();
        long docsBefore = documentRepository.count();
        long certAuditsBefore = countAudits("Certificate", "CERTIFICATE_GENERATED");
        long approvalAuditsBefore = countAudits("FinanceCase", "FINANCE_CASE_APPROVED");
        long notificationsBefore = notificationRepository.count();
        long receiptsBefore = receiptRepository.count();
        long certificatesBefore = certificateRepository.count();

        outageStorage.outage.set(true);
        try {
            mockMvc.perform(post("/api/internships/" + obligatoryCompleted.getId() + "/certificates")
                            .header("Authorization", "Bearer " + supervisorToken))
                    .andExpect(status().is5xxServerError());
            assertThat(certificateRepository.findByInternshipId(obligatoryCompleted.getId())).isEmpty();

            mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                            .header("Authorization", "Bearer " + financeToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().is5xxServerError());
            assertThat(receiptRepository.findByFinanceCaseId(caseId)).isEmpty();
            assertThat(caseStatus(caseId, financeToken)).isEqualTo("READY_FOR_DECISION");
        } finally {
            outageStorage.outage.set(false);
        }

        // No orphaned FileAsset/Document rows were left behind.
        assertThat(fileAssetRepository.count()).isEqualTo(assetsBefore);
        assertThat(documentRepository.count()).isEqualTo(docsBefore);
        assertThat(certificateRepository.count()).isEqualTo(certificatesBefore);
        assertThat(receiptRepository.count()).isEqualTo(receiptsBefore);

        // No audit record for either generation step was written: the storage
        // call throws before auditService.log(...) is ever reached in both
        // CertificateService.generateCertificate(...) and
        // FinanceService.approve(...)/issueReceipt(...), so this is not merely
        // "rolled back" but never attempted.
        assertThat(countAudits("Certificate", "CERTIFICATE_GENERATED")).isEqualTo(certAuditsBefore);
        assertThat(countAudits("FinanceCase", "FINANCE_CASE_APPROVED")).isEqualTo(approvalAuditsBefore);

        // No success notification was created either, for the same reason:
        // eventPublisher.publishEvent(...) sits after the storage call and is
        // never reached when storage throws.
        assertThat(notificationRepository.count()).isEqualTo(notificationsBefore);

        // Retrying after the outage clears must succeed cleanly and produce
        // exactly one artifact each (no duplicate from the earlier failed attempt).
        MvcResult retriedCert = mockMvc.perform(post("/api/internships/" + obligatoryCompleted.getId() + "/certificates")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(objectMapper.readTree(retriedCert.getResponse().getContentAsString()).get("reference").asText())
                .startsWith("CERT-");
        assertThat(certificateRepository.findByInternshipId(obligatoryCompleted.getId())).hasSize(1);

        MvcResult retriedApproval = mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(retriedApproval.getResponse().getContentAsString()).get("receiptReference").asText())
                .startsWith("PAY-");
        assertThat(receiptRepository.findByFinanceCaseId(caseId)).isPresent();
        assertThat(caseStatus(caseId, financeToken)).isEqualTo("APPROVED");
    }

    // -------------------------------------------------------------------------
    // A11 Final Verification: real PDF generation + manual QA export
    // -------------------------------------------------------------------------

    private static boolean hasEmbeddedLogoImage(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            var resources = document.getPage(0).getResources();
            for (var name : resources.getXObjectNames()) {
                if (resources.getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test
    @DisplayName("A11 final gate: real Certificate + PaymentReceipt PDFs are generated through the "
            + "production endpoints, contain no unresolved placeholders/logo/identity, and are saved "
            + "outside source control for manual visual QA")
    void realPdfGenerationForManualQa() throws Exception {
        // --- Certificate: real endpoint, no request body, server-generated date ---
        MvcResult certResult = mockMvc.perform(post("/api/internships/" + obligatoryCompleted.getId() + "/certificates")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode cert = objectMapper.readTree(certResult.getResponse().getContentAsString());
        String certRef = cert.get("reference").asText();
        byte[] certPdf = mockMvc.perform(get("/api/certificates/" + cert.get("id").asText())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(certPdf, 0, 5, StandardCharsets.UTF_8)).isEqualTo("%PDF-");
        assertThat(hasEmbeddedLogoImage(certPdf)).as("certificate must embed the STEG logo image").isTrue();
        String certText = pdfText(certPdf);
        assertThat(certText).contains("ATTESTATION DE STAGE");
        assertThat(certText).contains("Sami Gharbi");
        assertThat(certText).contains("Ing\u00e9nieur");
        assertThat(certText).contains(obligatoryCompleted.getStartDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        assertThat(certText).contains(obligatoryCompleted.getEndDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        assertThat(certText).contains(certRef);
        assertThat(certText).doesNotContainPattern("\\{\\{[a-zA-Z]+\\}\\}");

        // Downloaded bytes must be exactly the persisted FileAsset content (not a re-render).
        var certEntity = ((tn.steg.backend.certificate.domain.repository.CertificateRepository) certificateRepository)
                .findById(UUID.fromString(cert.get("id").asText())).orElseThrow();
        try (InputStream persisted = fileStorageServiceForAssertions().getInputStream(
                certEntity.getPdfFile().getStorageKey())) {
            assertThat(persisted.readAllBytes()).isEqualTo(certPdf);
        }

        // --- PaymentReceipt: real approval endpoint, amount/months/currency/reference ---
        UUID caseId = attachDossier(openCase(obligatoryCompleted.getId(), financeToken));
        MvcResult approved = mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Dossier verified, payment cleared.\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode approvedCase = objectMapper.readTree(approved.getResponse().getContentAsString());
        String receiptRef = approvedCase.get("receiptReference").asText();
        byte[] receiptPdf = mockMvc.perform(get("/api/finance-cases/" + caseId + "/receipt")
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(receiptPdf, 0, 5, StandardCharsets.UTF_8)).isEqualTo("%PDF-");
        assertThat(hasEmbeddedLogoImage(receiptPdf)).as("receipt must embed the STEG logo image").isTrue();
        String receiptText = pdfText(receiptPdf);
        assertThat(receiptText).contains("RE\u00c7U DE PAIEMENT");
        assertThat(receiptText).contains("Sami Gharbi");
        assertThat(receiptText).contains("3"); // payable months
        assertThat(receiptText).contains("150.00");
        assertThat(receiptText).contains("TND");
        assertThat(receiptText).contains(receiptRef);
        assertThat(receiptText).doesNotContainPattern("\\{\\{[a-zA-Z]+\\}\\}");
        assertThat(receiptRef).isNotEqualTo(certRef);

        // Receipt is only reachable while the case is APPROVED (already true here);
        // a case that never reached APPROVED has no receipt (see approvalReadinessAndRejection()).

        // --- Save both artifacts OUTSIDE source control for manual visual QA ---
        Path qaDir = Path.of(System.getProperty("user.home"), "steg-a11-qa-pdfs");
        Files.createDirectories(qaDir);
        Path certPath = qaDir.resolve("certificate-" + certRef + ".pdf");
        Path receiptPath = qaDir.resolve("receipt-" + receiptRef + ".pdf");
        Files.write(certPath, certPdf);
        Files.write(receiptPath, receiptPdf);
        System.out.println("[A11 QA] Certificate saved to: " + certPath);
        System.out.println("[A11 QA] Receipt saved to: " + receiptPath);
    }

    private FileStorageService fileStorageServiceForAssertions() {
        return outageStorage;
    }

    // -------------------------------------------------------------------------
    // A11 Final Verification: APPROVED case immutability (extended gate)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("A11 final gate: an APPROVED case is permanently immutable — dossier edits, "
            + "rejection, re-approval and unrelated internship mutations never alter the decided snapshot")
    void approvedCaseIsPermanentlyImmutableExtended() throws Exception {
        UUID caseId = attachDossier(openCase(obligatoryCompleted.getId(), financeToken));
        MvcResult approved = mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        String receiptRef = objectMapper.readTree(approved.getResponse().getContentAsString())
                .get("receiptReference").asText();
        var snapshotBefore = calculationRepository
                .findAllByFinanceCaseIdOrderByCalculationSequenceAsc(caseId);
        var lastSnapshotBefore = snapshotBefore.get(snapshotBefore.size() - 1);

        // Recalculation is rejected on a decided case.
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/recalculate")
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FINANCE_CASE_DECIDED"));

        // Attaching/replacing a mandatory dossier document is rejected on a decided case.
        UUID lateDoc = uploadDocument(supervisorToken, PDF_BYTES, "late.pdf", "application/pdf", "INTERNSHIP_APPLICATION");
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/documents")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + lateDoc + "\",\"mandatory\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FINANCE_CASE_DECIDED"));

        // Rejection after approval is rejected (approval is terminal).
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/reject")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"too late\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FINANCE_CASE_DECIDED"));

        // A second approval attempt is rejected (approval is terminal).
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FINANCE_CASE_DECIDED"));

        // Mutating the underlying internship's dates/type AFTER the decision must never
        // alter the already-persisted calculation snapshot or the issued receipt: the
        // snapshot is stored data, never recomputed from the (now-changed) internship.
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(obligatoryCompleted.getId()).orElseThrow();
        internship.setStartDate(LocalDate.of(2020, 1, 1));
        internship.setEndDate(LocalDate.of(2020, 12, 31));
        internshipRepository.saveAndFlush(internship);

        var snapshotAfter = calculationRepository
                .findAllByFinanceCaseIdOrderByCalculationSequenceAsc(caseId);
        assertThat(snapshotAfter).hasSize(snapshotBefore.size());
        var lastSnapshotAfter = snapshotAfter.get(snapshotAfter.size() - 1);
        assertThat(lastSnapshotAfter.getCappedAmount()).isEqualByComparingTo(lastSnapshotBefore.getCappedAmount());
        assertThat(lastSnapshotAfter.getPayableMonths()).isEqualTo(lastSnapshotBefore.getPayableMonths());
        assertThat(lastSnapshotAfter.getCompletedMonths()).isEqualTo(lastSnapshotBefore.getCompletedMonths());

        // The GET endpoint still reports the immutable decided figures, and the
        // receipt PDF (already generated) still carries the original approved amount.
        mockMvc.perform(get("/api/finance-cases/" + caseId)
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculation.cappedAmount").value(lastSnapshotBefore.getCappedAmount().doubleValue()))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        byte[] receiptPdf = mockMvc.perform(get("/api/finance-cases/" + caseId + "/receipt")
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(pdfText(receiptPdf)).contains("150.00").contains(receiptRef);
    }
}
