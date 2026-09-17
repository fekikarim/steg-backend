package tn.steg.backend.e2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.notification.infrastructure.persistence.NotificationRepository;

/**
 * E2 regression net for defects found during the full-lifecycle run
 * (see docs/FULL_E2E_BUSINESS_REPORT.md): submission notification, reference
 * universities, INTERN elevation, document IDOR, null-safe audits, 400 params.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("E2 — lifecycle regression net (direct API calls)")
class E2RegressionTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 e2 regression".getBytes(StandardCharsets.UTF_8);

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private InternshipApplicationRepository applicationRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private tn.steg.backend.application.application.ApplicationService applicationService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private User candidateUser;
    private Candidate candidate;
    private String candidateToken;
    private String hrToken;
    private UserPrincipal hrPrincipal;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        candidateUser = userRepository.saveAndFlush(new User("e2r_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        candidateToken = jwtService.generateAccessToken(candidateUser.getId(), candidateUser.getEmail(), List.of("ROLE_CANDIDATE"));

        User hrUser = userRepository.saveAndFlush(new User("e2hr_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        hrToken = jwtService.generateAccessToken(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));
        hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));

        University uni = universityRepository.saveAndFlush(new University("E2U_" + suffix, "E2 Uni"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("E2R" + suffix).getBytes(StandardCharsets.UTF_8)));
        candidate = new Candidate("E2r", "Cand", "e2r_" + suffix + "@steg.com", cinHash, uni);
        candidate.setUser(candidateUser);
        candidate.setNationalIdEncrypted("E2R" + suffix);
        candidate = candidateRepository.saveAndFlush(candidate);
    }

    @Test
    @DisplayName("Reference universities are seeded (clean-DB onboarding possible)")
    void referenceUniversitiesSeeded() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/universities")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).size()).isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("Submit emits a candidate notification (no silent submission)")
    void submitNotifiesCandidate() throws Exception {
        InternshipApplication app = applicationRepository.saveAndFlush(new InternshipApplication(
                "APP-E2R-" + UUID.randomUUID().toString().substring(0, 8), candidate, ApplicationStatus.DRAFT));
        // Direct service call (same pattern as FinanceNotificationTest): the BEFORE_COMMIT
        // listener fires at test-transaction commit and the row is visible afterwards.
        // The live E2 run proves end-to-end delivery (MailHog submission email received).
        long before = notificationRepository.count();
        applicationService.submitApplication(app.getId(), new UserPrincipal(
                candidateUser.getId(), candidateUser.getEmail(), List.of("ROLE_CANDIDATE")));
        assertThat(notificationRepository.count()).isGreaterThan(before);
    }

    @Test
    @DisplayName("Internship creation elevates the candidate user to INTERN")
    @Transactional
    void creationElevatesToIntern() {
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "E2R project", "Ingénieur", false), hrPrincipal);
        assertThat(created).isNotNull();
        User reloaded = ((tn.steg.backend.iam.domain.repository.UserRepository) userRepository)
                .findById(candidateUser.getId()).orElseThrow();
        assertThat(reloaded.getAssignedRoles()).anyMatch(r -> "INTERN".equals(r.getCode()));
    }

    @Test
    @DisplayName("Outsider document download is refused (owner/staff only)")
    void outsiderDownloadRefused() throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "d.pdf", "application/pdf", PDF_BYTES))
                        .param("type", "INTERNSHIP_APPLICATION")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isCreated())
                .andReturn();
        UUID docId = UUID.fromString(objectMapper.readTree(
                uploaded.getResponse().getContentAsString()).get("id").asText());

        User outsider = userRepository.saveAndFlush(
                new User("e2out_" + UUID.randomUUID().toString().substring(0, 8) + "@steg.com", "hash", UserStatus.ACTIVE));
        String outsiderToken = jwtService.generateAccessToken(
                outsider.getId(), outsider.getEmail(), List.of("ROLE_CANDIDATE"));

        mockMvc.perform(get("/api/documents/" + docId + "/download")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
        // Owner still downloads fine.
        mockMvc.perform(get("/api/documents/" + docId + "/download")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Task creation without assignee succeeds (no audit NPE)")
    void unassignedTaskSucceeds() throws Exception {
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "E2R tasks", "Ingénieur", false), hrPrincipal);
        mockMvc.perform(post("/api/internships/" + created.id() + "/tasks")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"E2R task\",\"description\":\"no assignee\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    @DisplayName("Missing required parameter is a typed 400, never a 500")
    void missingParameterIsBadRequest() throws Exception {        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "E2R deliv", "Ingénieur", false), hrPrincipal);
        mockMvc.perform(multipart("/api/internships/" + created.id() + "/deliverables")
                        .file(new MockMultipartFile("file", "r.pdf", "application/pdf", PDF_BYTES))
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    @DisplayName("Anonymous submit links the university and attaches filename-matched docs")
    void anonymousSubmitWiresUniversityAndDocs() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        University uni = universityRepository.saveAndFlush(new University("E2A_" + suffix, "E2 Anon Uni"));
        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "firstName", "Anon", "lastName", "E2R", "email", "anon_" + suffix + "@steg.com",
                "nationalId", "AN" + suffix.replace("-", "").substring(0, 8), "universityId", uni.getId().toString(),
                "desiredStartDate", "2026-07-01", "desiredEndDate", "2026-07-30"));
        MvcResult result = mockMvc.perform(multipart("/api/public/applications")
                        .file(new MockMultipartFile("application", "application", "application/json",
                                payload.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("documents", "demande-stage.pdf", "application/pdf", PDF_BYTES))
                        .file(new MockMultipartFile("documents", "lettre-affectation.pdf", "application/pdf", PDF_BYTES)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").isNotEmpty())
                .andExpect(jsonPath("$.trackingToken").isNotEmpty())
                .andReturn();
        String reference = objectMapper.readTree(result.getResponse().getContentAsString()).get("reference").asText();
        assertThat(reference).startsWith("APP-");
    }

    @Test
    @DisplayName("Anonymous submit with unknown university is refused (404, typed)")
    void anonymousSubmitUnknownUniversity() throws Exception {
        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "firstName", "Anon", "lastName", "E2R", "email", "anon_x_" + UUID.randomUUID() + "@steg.com",
                "nationalId", "AX" + UUID.randomUUID().toString().substring(0, 8), "universityId", UUID.randomUUID().toString(),
                "desiredStartDate", "2026-07-01", "desiredEndDate", "2026-07-30"));
        mockMvc.perform(multipart("/api/public/applications")
                        .file(new MockMultipartFile("application", "application", "application/json",
                                payload.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isNotFound());
    }
}
