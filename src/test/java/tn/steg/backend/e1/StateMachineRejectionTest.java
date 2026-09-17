package tn.steg.backend.e1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.infrastructure.persistence.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.certificate.application.CertificateService;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.finance.application.FinanceService;
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
import tn.steg.backend.workflow.domain.model.ApprovalDecision;
import tn.steg.backend.workflow.domain.model.WorkflowActionType;

/**
 * E1.2 — every illegal state transition, proven by a failing direct API call.
 *
 * <p>Each test performs the real HTTP request with a real JWT (correct-role and
 * wrong-role variants) and asserts the typed rejection (422 + error code, or 403).
 * "The UI hides the button" is not evidence; these refusals are.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("E1.2 — State machine rejection probes (direct API calls)")
class StateMachineRejectionTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 e1 dossier file content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG_BYTES =
            new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipApplicationRepository applicationRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private WorkflowService workflowService;
    @Autowired private FinanceService financeService;
    @Autowired private CertificateService certificateService;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private User candidateUser;
    private Candidate candidate;
    private String candidateToken;
    private String hrToken;
    private String financeToken;
    private String supervisorToken;
    private UserPrincipal hrPrincipal;
    private UserPrincipal financePrincipal;
    private UserPrincipal supervisorPrincipal;
    private Department dept;
    private Employee supervisorEmployee;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        candidateUser = userRepository.saveAndFlush(new User("cand_e1_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        candidateToken = jwtService.generateAccessToken(candidateUser.getId(), candidateUser.getEmail(), List.of("ROLE_CANDIDATE"));

        User hrUser = userRepository.saveAndFlush(new User("hr_e1_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        hrToken = jwtService.generateAccessToken(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));
        hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));

        User financeUser = userRepository.saveAndFlush(new User("fin_e1_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        financeToken = jwtService.generateAccessToken(financeUser.getId(), financeUser.getEmail(), List.of("ROLE_FINANCE"));
        financePrincipal = new UserPrincipal(financeUser.getId(), financeUser.getEmail(), List.of("ROLE_FINANCE"));
        User supervisorUser = userRepository.saveAndFlush(new User("sup_e1_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        supervisorToken = jwtService.generateAccessToken(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));
        supervisorPrincipal = new UserPrincipal(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        dept = departmentRepository.saveAndFlush(new Department("DIR_E1_" + suffix, "E1 Dept", "E1"));
        supervisorEmployee = new Employee("EMP-E1-S-" + suffix, "E1", "Sup", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);
        Employee financeEmployee = new Employee("EMP-E1-F-" + suffix, "E1", "Fin", dept);
        financeEmployee.setUser(financeUser);
        employeeRepository.saveAndFlush(financeEmployee);

        University uni = universityRepository.saveAndFlush(new University("UNI_E1_" + suffix, "E1 Uni"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("E1" + suffix).getBytes(StandardCharsets.UTF_8)));
        candidate = new Candidate("E1", "Candidate", "cand_e1_" + suffix + "@steg.com", cinHash, uni);
        candidate.setUser(candidateUser);
        candidate.setNationalIdEncrypted("E1" + suffix);
        candidate = candidateRepository.saveAndFlush(candidate);
    }

    private InternshipApplication draftApplication() {
        InternshipApplication app = new InternshipApplication(
                "APP-E1-" + UUID.randomUUID().toString().substring(0, 8), candidate, ApplicationStatus.DRAFT);
        app.setDesiredStartDate(LocalDate.of(2026, 6, 1));
        app.setDesiredEndDate(LocalDate.of(2026, 6, 30));
        return applicationRepository.saveAndFlush(app);
    }

    private Internship plannedInternship() {
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "E1 project", "Ingénieur", false), hrPrincipal);
        // NB: no assign() here — assignment activates the internship outside the workflow
        // (documented in the matrix); probes need a genuine PLANNED state.
        return ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();
    }

    private void transitionInternship(UUID id, String step, WorkflowActionType type, ApprovalDecision decision) throws Exception {
        mockMvc.perform(post("/api/internships/" + id + "/workflow/actions")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorkflowTransitionRequest(step, type, decision, "e1"))))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Application machine
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A1: submitting twice is refused (DRAFT→SUBMITTED is one-way)")
    void submitTwiceRefused() throws Exception {
        InternshipApplication app = draftApplication();
        mockMvc.perform(post("/api/applications/" + app.getId() + "/submit")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/applications/" + app.getId() + "/submit")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("A2: withdrawing an ACCEPTED application is refused")
    void withdrawAcceptedRefused() throws Exception {
        InternshipApplication app = draftApplication();
        app.setStatus(ApplicationStatus.ACCEPTED);
        applicationRepository.saveAndFlush(app);
        mockMvc.perform(post("/api/applications/" + app.getId() + "/withdraw")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("APPLICATION_WITHDRAWAL_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("A3: candidate driving a staff workflow action is refused server-side (403)")
    void candidateWorkflowActionForbidden() throws Exception {
        InternshipApplication app = draftApplication();
        app.setStatus(ApplicationStatus.SUBMITTED);
        applicationRepository.saveAndFlush(app);
        workflowService.spawnApplicationWorkflow(app);
        mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorkflowTransitionRequest(
                                "UNDER_REVIEW", WorkflowActionType.VALIDATION, null, "e1"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A4: unknown workflow step is rejected with a typed error")
    void unknownStepRejected() throws Exception {        InternshipApplication app = draftApplication();
        app.setStatus(ApplicationStatus.SUBMITTED);
        applicationRepository.saveAndFlush(app);
        workflowService.spawnApplicationWorkflow(app);
        mockMvc.perform(post("/api/applications/" + app.getId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorkflowTransitionRequest(
                                "NONSENSE", WorkflowActionType.VALIDATION, null, "e1"))))
                .andExpect(status().isUnprocessableEntity())
                // Step existence is checked before guard evaluation.
                .andExpect(jsonPath("$.error").value("WORKFLOW_STEP_NOT_FOUND"));
    }

    @Test
    @DisplayName("A5: reading another internship's workflow state is refused (404, no oracle)")
    void foreignInternshipWorkflowHidden() throws Exception {
        Internship internship = plannedInternship();
        User outsiderUser = userRepository.saveAndFlush(new User(
                "outsider_wf_" + UUID.randomUUID().toString().substring(0, 8) + "@steg.com",
                "hash", UserStatus.ACTIVE));
        String outsiderToken = jwtService.generateAccessToken(outsiderUser.getId(),
                outsiderUser.getEmail(), List.of("ROLE_CANDIDATE"));
        mockMvc.perform(get("/api/internships/" + internship.getId() + "/workflow")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Internship machine
    // ------------------------------------------------------------------

    @Test
    @DisplayName("I1: completing a PLANNED internship directly is refused")
    void completePlannedRefused() throws Exception {
        Internship internship = plannedInternship();
        mockMvc.perform(post("/api/internships/" + internship.getId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorkflowTransitionRequest(
                                "COMPLETED", WorkflowActionType.COMPLETION, null, "e1"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ILLEGAL_WORKFLOW_TRANSITION"));
    }

    @Test
    @DisplayName("I2: reactivating a COMPLETED internship is refused")
    void reactivateCompletedRefused() throws Exception {
        Internship internship = plannedInternship();
        transitionInternship(internship.getId(), "ACTIVE", WorkflowActionType.VALIDATION, null);
        transitionInternship(internship.getId(), "COMPLETED", WorkflowActionType.COMPLETION, null);
        mockMvc.perform(post("/api/internships/" + internship.getId() + "/workflow/actions")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorkflowTransitionRequest(
                                "ACTIVE", WorkflowActionType.VALIDATION, null, "e1"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ILLEGAL_WORKFLOW_TRANSITION"));
    }

    @Test
    @DisplayName("I3: changing dates of a COMPLETED internship is refused")
    void datesOfCompletedRefused() throws Exception {
        Internship internship = plannedInternship();
        transitionInternship(internship.getId(), "ACTIVE", WorkflowActionType.VALIDATION, null);
        transitionInternship(internship.getId(), "COMPLETED", WorkflowActionType.COMPLETION, null);
        mockMvc.perform(put("/api/internships/" + internship.getId() + "/dates")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-02-01\",\"endDate\":\"2026-05-01\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INTERNSHIP_TERMINATED"));
    }

    // ------------------------------------------------------------------
    // Finance machine
    // ------------------------------------------------------------------

    private UUID certifiedCompletedInternshipId() {
        Internship internship = plannedInternship();
        try {
            transitionInternship(internship.getId(), "ACTIVE", WorkflowActionType.VALIDATION, null);
            transitionInternship(internship.getId(), "COMPLETED", WorkflowActionType.COMPLETION, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        certificateService.generateCertificate(internship.getId(), supervisorPrincipal);
        return internship.getId();
    }

    @Test
    @DisplayName("P1: approving a case whose dossier is not READY is refused")
    void approveNotReadyRefused() throws Exception {
        UUID internshipId = certifiedCompletedInternshipId();
        MvcResult opened = mockMvc.perform(post("/api/finance-cases")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internshipId\":\"" + internshipId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID caseId = UUID.fromString(objectMapper.readTree(
                opened.getResponse().getContentAsString()).get("id").asText());
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"too early\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FINANCE_CASE_NOT_READY"));
    }

    @Test
    @DisplayName("P2: deciding an already-APPROVED case is refused (decided-immutable)")
    void doubleApproveRefused() throws Exception {
        UUID internshipId = certifiedCompletedInternshipId();
        MvcResult opened = mockMvc.perform(post("/api/finance-cases")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internshipId\":\"" + internshipId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID caseId = UUID.fromString(objectMapper.readTree(
                opened.getResponse().getContentAsString()).get("id").asText());
        attachVerifiedDossier(caseId);
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"ok\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"again\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FINANCE_CASE_DECIDED"));
    }

    @Test
    @DisplayName("P3: a candidate approving a payment is refused server-side (403)")
    void candidateApproveForbidden() throws Exception {
        UUID internshipId = certifiedCompletedInternshipId();
        MvcResult opened = mockMvc.perform(post("/api/finance-cases")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internshipId\":\"" + internshipId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID caseId = UUID.fromString(objectMapper.readTree(
                opened.getResponse().getContentAsString()).get("id").asText());
        mockMvc.perform(post("/api/finance-cases/" + caseId + "/approve")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"self-approve\"}"))
                .andExpect(status().isForbidden());
    }

    private void attachVerifiedDossier(UUID caseId) throws Exception {
        UUID cin = upload("cin.png", "image/png", "CIN_COPY");
        UUID application = upload("application.pdf", "application/pdf", "INTERNSHIP_APPLICATION");
        UUID assignment = upload("assignment.pdf", "application/pdf", "ASSIGNMENT_LETTER");
        UUID report = upload("report.pdf", "application/pdf", "STEG_INTERNSHIP_REPORT");
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
                    .andExpect(status().isOk());
        }
    }

    private UUID upload(String fileName, String contentType, String docType) throws Exception {
        byte[] bytes = "image/png".equals(contentType) ? PNG_BYTES : PDF_BYTES;
        MvcResult result = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", fileName, contentType, bytes))
                        .param("type", docType)
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(objectMapper.readTree(
                result.getResponse().getContentAsString()).get("id").asText());
        assertThat(id).isNotNull();
        return id;
    }
}
