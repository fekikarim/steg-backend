package tn.steg.backend.companion.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.companion.domain.model.Logbook;
import tn.steg.backend.companion.domain.model.LogbookStatus;
import tn.steg.backend.companion.infrastructure.persistence.LogbookRepository;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T10/ST-VAL-04 — supervisor rejections notify the intern with the reason
 * (Testcontainers, real Postgres).
 *
 * <p>Deliberately NOT {@code @Transactional}: the fan-out runs in
 * {@code BEFORE_COMMIT} listeners, which only fire when the publishing
 * transaction commits. Fixtures use random suffixes per test so committed
 * rows never collide.
 *
 * <p>Before this slice, a supervisor rejection stored the reason
 * audit-only (journal entries, deliverables): the student saw REJECTED
 * with no explanation of what to fix. Rejections now publish the existing
 * {@code DOCUMENT_REJECTED} catalogue key with supervisor wording.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("T10 — Supervisor rejection notifies the intern with the reason")
class SupervisorRejectNotificationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private LogbookRepository logbookRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;

    private String internToken;
    private String supervisorToken;
    private String outsiderToken;
    private String supervisorEmail;
    private UUID internshipId;
    private User internUser;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        internUser = userRepository.saveAndFlush(new User("t10_intern_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));

        User supUser = userRepository.saveAndFlush(new User("t10_sup_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        supervisorEmail = supUser.getEmail();
        supervisorToken = jwtService.generateAccessToken(supUser.getId(), supUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        User outsider = userRepository.saveAndFlush(new User("t10_out_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        outsiderToken = jwtService.generateAccessToken(outsider.getId(), outsider.getEmail(), List.of("ROLE_SUPERVISOR"));

        University uni = universityRepository.saveAndFlush(new University("UNI_T10_" + suffix, "T10 Uni"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("T10" + suffix).getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Rejet", "Notifie", internUser.getEmail(), cinHash, uni);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("T10" + suffix);
        candidate = candidateRepository.saveAndFlush(candidate);

        Department dept = departmentRepository.saveAndFlush(new Department("DEPT_T10_" + suffix, "T10 Dept", "T10"));
        Employee supervisor = new Employee("EMP_T10_" + suffix, "Sana", "Sup", dept);
        supervisor.setUser(supUser);
        supervisor = employeeRepository.saveAndFlush(supervisor);

        User hrUser = userRepository.saveAndFlush(new User("t10_hr_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        var hrPrincipal = new tn.steg.backend.common.domain.model.UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_ADMIN"));

        InternshipResponse internship = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.now().minusDays(30),
                LocalDate.now().plusDays(60),
                "T10 project",
                "Ingénieur",
                false), hrPrincipal);
        internshipId = internship.id();

        internshipService.assign(internshipId, new InternshipAssignmentRequest(
                dept.getId(), supervisor.getId(),
                LocalDate.now().minusDays(30), LocalDate.now().plusDays(60),
                "T10 assignment"), hrPrincipal);
    }

    @Test
    @DisplayName("R1: rejecting a deliverable notifies the intern with the reason")
    void deliverableRejectNotifiesIntern() throws Exception {
        UUID deliverableId = createAndSubmitDeliverable("Missing the STEG header");

        mockMvc.perform(post("/api/internships/deliverables/" + deliverableId + "/reject")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Add the STEG header page\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        String payload = notifications(internToken);
        assertThat(payload).contains("DOCUMENT_REJECTED");
        assertThat(payload).contains("Add the STEG header page");
        assertThat(payload).contains(deliverableId.toString());
        // The reviewer stays anonymous: the reason travels, never the identity.
        assertThat(payload).doesNotContain(supervisorEmail);
    }

    @Test
    @DisplayName("R2: rejecting a journal entry notifies the intern with the reason")
    void journalRejectNotifiesIntern() throws Exception {
        String created = mockMvc.perform(post("/api/internships/" + internshipId + "/journal/entries")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Jour 3\",\"description\":\"Fait X\",\"entryDate\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID entryId = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        mockMvc.perform(post("/api/internships/journal/entries/" + entryId + "/submit")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/internships/journal/entries/" + entryId + "/reject")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Detail the difficulties met\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        String payload = notifications(internToken);
        assertThat(payload).contains("DOCUMENT_REJECTED");
        assertThat(payload).contains("Detail the difficulties met");
        assertThat(payload).contains(entryId.toString());
        assertThat(payload).doesNotContain(supervisorEmail);
    }

    @Test
    @DisplayName("R3: validating notifies nothing new (no DOCUMENT_REJECTED)")
    void validateNotifiesNothingNew() throws Exception {
        UUID deliverableId = createAndSubmitDeliverable("great report");

        mockMvc.perform(post("/api/internships/deliverables/" + deliverableId + "/validate")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));

        assertThat(notifications(internToken)).doesNotContain("DOCUMENT_REJECTED");
    }

    @Test
    @DisplayName("R4: an out-of-scope supervisor cannot reject (403/404) and notifies nobody")
    void outsiderCannotReject() throws Exception {
        UUID deliverableId = createAndSubmitDeliverable("outsider target");

        mockMvc.perform(post("/api/internships/deliverables/" + deliverableId + "/reject")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"not yours\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(403, 404));

        assertThat(notifications(internToken)).doesNotContain("DOCUMENT_REJECTED");
    }

    @Test
    @DisplayName("R5: rejecting a logbook notifies the intern with the reason")
    void logbookRejectNotifiesIntern() throws Exception {
        Internship internship = internshipRepository.findById(internshipId).orElseThrow();
        Logbook logbook = new Logbook(null, internship, internUser, null);
        logbook.setStatus(LogbookStatus.SUBMITTED);
        logbook.setFinalText("Reviewed logbook text for T10.");
        logbook = logbookRepository.saveAndFlush(logbook);

        mockMvc.perform(post("/api/internships/" + internshipId + "/logbook/" + logbook.getId() + "/reject")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Expand week two\"}"))
                .andExpect(status().isOk());

        String payload = notifications(internToken);
        assertThat(payload).contains("DOCUMENT_REJECTED");
        assertThat(payload).contains("Expand week two");
        assertThat(payload).contains(logbook.getId().toString());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private UUID createAndSubmitDeliverable(String title) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf",
                "%PDF-1.4 t10 sample".getBytes(StandardCharsets.UTF_8));
        String created = mockMvc.perform(multipart("/api/internships/" + internshipId + "/deliverables")
                        .file(file)
                        .param("title", title)
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(created).get("id").asText());
        mockMvc.perform(post("/api/internships/deliverables/" + id + "/submit")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());
        return id;
    }

    private String notifications(String bearer) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }
}
