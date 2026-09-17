package tn.steg.backend.e1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import tn.steg.backend.common.domain.model.UserPrincipal;
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
 * E1.6 — idempotency keys on critical mutations, proven over HTTP.
 *
 * <p>Same {@code X-Idempotency-Key} twice → identical response, single side effect.
 * The no-key control still fails safe (422/409), proving the key is what dedupes.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("E1.6 — Idempotency probes (direct API calls)")
class IdempotencyTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 e1 idempotency file".getBytes(StandardCharsets.UTF_8);

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
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private User candidateUser;
    private Candidate candidate;
    private String candidateToken;
    private String supervisorToken;
    private UserPrincipal hrPrincipal;
    private UserPrincipal supervisorPrincipal;
    private Department dept;
    private Employee supervisorEmployee;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        candidateUser = userRepository.saveAndFlush(new User("cand_k_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        candidateToken = jwtService.generateAccessToken(candidateUser.getId(), candidateUser.getEmail(), List.of("ROLE_CANDIDATE"));

        User hrUser = userRepository.saveAndFlush(new User("hr_k_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));

        User supervisorUser = userRepository.saveAndFlush(new User("sup_k_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        supervisorToken = jwtService.generateAccessToken(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));
        supervisorPrincipal = new UserPrincipal(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_K_" + suffix, "K Dept", "K"));
        Employee supervisorEmployee = employeeRepository.saveAndFlush(new Employee("EMP-K-S-" + suffix, "K", "Sup", dept));
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);
        this.dept = dept;
        this.supervisorEmployee = supervisorEmployee;

        University uni = universityRepository.saveAndFlush(new University("UNI_K_" + suffix, "K Uni"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("K" + suffix).getBytes(StandardCharsets.UTF_8)));
        candidate = new Candidate("K", "Candidate", "cand_k_" + suffix + "@steg.com", cinHash, uni);
        candidate.setUser(candidateUser);
        candidate.setNationalIdEncrypted("K" + suffix);
        candidate = candidateRepository.saveAndFlush(candidate);
    }

    @Test
    @DisplayName("Duplicate submit with the same key replays one SUBMITTED application")
    void submitReplay() throws Exception {
        InternshipApplication app = applicationRepository.saveAndFlush(new InternshipApplication(
                "APP-K-" + UUID.randomUUID().toString().substring(0, 8), candidate, ApplicationStatus.DRAFT));
        app.setDesiredStartDate(LocalDate.of(2026, 6, 1));
        app.setDesiredEndDate(LocalDate.of(2026, 6, 30));
        app = applicationRepository.saveAndFlush(app);

        String first = mockMvc.perform(post("/api/applications/" + app.getId() + "/submit")
                        .header("Authorization", "Bearer " + candidateToken)
                        .header("X-Idempotency-Key", "k-submit-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn().getResponse().getContentAsString();

        // Control: same request WITHOUT the key is refused (proves single execution matters).
        mockMvc.perform(post("/api/applications/" + app.getId() + "/submit")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));

        // Replay: same key returns the identical stored response instead of failing.
        String second = mockMvc.perform(post("/api/applications/" + app.getId() + "/submit")
                        .header("Authorization", "Bearer " + candidateToken)
                        .header("X-Idempotency-Key", "k-submit-1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second)).isEqualTo(objectMapper.readTree(first));
    }

    @Test
    @DisplayName("Duplicate upload with the same key replays one document (no second row)")
    void uploadReplay() throws Exception {
        MvcResult first = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "app.pdf", "application/pdf", PDF_BYTES))
                        .param("type", "INTERNSHIP_APPLICATION")
                        .header("Authorization", "Bearer " + candidateToken)
                        .header("X-Idempotency-Key", "k-upload-1"))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        MvcResult second = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "app.pdf", "application/pdf", PDF_BYTES))
                        .param("type", "INTERNSHIP_APPLICATION")
                        .header("Authorization", "Bearer " + candidateToken)
                        .header("X-Idempotency-Key", "k-upload-1"))
                .andExpect(status().isCreated())
                .andReturn();
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();

        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    @DisplayName("Duplicate certificate generation with the same key replays one reference")
    void certificateReplay() throws Exception {
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "K project", "Ingénieur", false), hrPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();
        // ACTIVE assignment for the controller's isSupervisorOf check (assign also
        // flips status to ACTIVE, which the COMPLETED transition below expects).
        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), "k"), hrPrincipal);
        workflowService.transitionInternship(internship.getId(),
                new WorkflowTransitionRequest("COMPLETED", WorkflowActionType.COMPLETION, null, "k"), hrPrincipal);

        String first = mockMvc.perform(post("/api/internships/" + internship.getId() + "/certificates")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .header("X-Idempotency-Key", "k-cert-1"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reference = objectMapper.readTree(first).get("reference").asText();

        // Control: without the key the duplicate is refused outright.
        mockMvc.perform(post("/api/internships/" + internship.getId() + "/certificates")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CERTIFICATE_ALREADY_EXISTS"));

        // Replay: same key returns the identical certificate instead of failing.
        String second = mockMvc.perform(post("/api/internships/" + internship.getId() + "/certificates")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .header("X-Idempotency-Key", "k-cert-1"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(second).get("reference").asText()).isEqualTo(reference);
    }
}
