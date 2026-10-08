package tn.steg.backend.internship.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
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
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T13/B13 — {@code GET /api/internships/{id}/summary} (Testcontainers).
 *
 * <p>One scoped read for the whole student home: every section reuses the
 * exact service call the corresponding list screen uses, so home numbers
 * cannot drift from the lists (same code paths, same intern visibility:
 * hidden scheduled tasks excluded, done means {@code APPROVED} only).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("T13 — Student home summary (own scope + list-identical aggregates)")
class InternshipSummaryTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private String internToken;
    private String otherInternToken;
    private String supervisorToken;
    private UUID internshipId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User internUser = userRepository.saveAndFlush(new User("t13_intern_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));

        User otherUser = userRepository.saveAndFlush(new User("t13_other_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        otherInternToken = jwtService.generateAccessToken(otherUser.getId(), otherUser.getEmail(), List.of("ROLE_INTERN"));

        User supUser = userRepository.saveAndFlush(new User("t13_sup_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        supervisorToken = jwtService.generateAccessToken(supUser.getId(), supUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        University uni = universityRepository.saveAndFlush(new University("UNI_T13_" + suffix, "T13 Uni"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("T13" + suffix).getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Home", "Etudiant", internUser.getEmail(), cinHash, uni);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("T13" + suffix);
        candidate = candidateRepository.saveAndFlush(candidate);

        Department dept = departmentRepository.saveAndFlush(new Department("DEPT_T13_" + suffix, "T13 Dept", "T13"));
        Employee supervisor = new Employee("EMP_T13_" + suffix, "Sana", "Sup", dept);
        supervisor.setUser(supUser);
        supervisor = employeeRepository.saveAndFlush(supervisor);

        User hrUser = userRepository.saveAndFlush(new User("t13_hr_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        var hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_ADMIN"));

        InternshipResponse internship = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.now().minusDays(30),
                LocalDate.now().plusDays(60),
                "T13 project",
                "Ingénieur",
                false), hrPrincipal);
        internshipId = internship.id();

        internshipService.assign(internshipId, new InternshipAssignmentRequest(
                dept.getId(), supervisor.getId(),
                LocalDate.now().minusDays(30), LocalDate.now().plusDays(60),
                "T13 assignment"), hrPrincipal);
    }

    @Test
    @DisplayName("H1: the intern reads his own summary with every section")
    void internReadsOwnSummary() throws Exception {
        mockMvc.perform(get("/api/internships/" + internshipId + "/summary")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internship.id").value(internshipId.toString()))
                .andExpect(jsonPath("$.assignments").isArray())
                .andExpect(jsonPath("$.tasks.content").isArray())
                .andExpect(jsonPath("$.journal.content").isArray())
                .andExpect(jsonPath("$.deliverables.content").isArray())
                .andExpect(jsonPath("$.evaluations.content").isArray())
                .andExpect(jsonPath("$.notifications.content").isArray());
    }

    @Test
    @DisplayName("H2: another intern, the supervisor and anonymous callers are refused")
    void foreignAccessRefused() throws Exception {
        mockMvc.perform(get("/api/internships/" + internshipId + "/summary")
                        .header("Authorization", "Bearer " + otherInternToken))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(403, 404));
        mockMvc.perform(get("/api/internships/" + internshipId + "/summary")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/internships/" + internshipId + "/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("H3: aggregates match the lists (hidden excluded, APPROVED-only done)")
    void aggregatesMatchListSemantics() throws Exception {
        UUID approved = createTask(supervisorToken, "Approved work", null);
        UUID completed = createTask(supervisorToken, "Done awaiting review", null);
        createTask(supervisorToken, "Fresh todo", null);
        createTask(supervisorToken, "Future scheduled",
                Instant.now().plusSeconds(30 * 24 * 3600).toString());
        completeAsIntern(approved);
        completeAsIntern(completed);
        reviewTask(true, approved);

        String draft = createJournalEntry("Draft day", "work");
        String submitted = createJournalEntry(internToken, "Submitted day", "work");
        submitJournalEntry(submitted);
        String validated = createJournalEntry(internToken, "Validated day", "work");
        submitJournalEntry(validated);
        validateJournalEntry(validated, "Bien.");

        createAndSubmitDeliverable("Report Submitted");
        uploadDeliverable("Report Draft");

        mockMvc.perform(post("/api/internships/" + internshipId + "/evaluations")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"WEEKLY\",\"evaluationDate\":\"" + LocalDate.now() + "\",\"feedback\":\"Bon.\"}"))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/internships/" + internshipId + "/summary")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andReturn();
                        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        // Intern view: the hidden scheduled task is excluded everywhere.
        assertThat(root.path("tasksGrandTotal").asLong()).isEqualTo(3);
        // Done means APPROVED only — COMPLETED awaiting review does not count.
        assertThat(root.path("tasksCompletedTotal").asLong()).isEqualTo(1);
        assertThat(root.path("tasks").path("page").path("totalElements").asLong()).isEqualTo(3);
        assertThat(root.path("tasks").path("content")).hasSize(3);
        // Journal: draft pending, submitted is supervisor-side; validated apart.
        assertThat(root.path("pendingJournalTotal").asLong()).isEqualTo(1);
        assertThat(root.path("journalValidatedTotal").asLong()).isEqualTo(1);
        assertThat(root.path("journal").path("content")).hasSize(3);
        // Deliverables: both rows, total 2.
        assertThat(root.path("deliverablesTotal").asLong()).isEqualTo(2);
        assertThat(root.path("deliverables").path("page").path("totalElements").asLong()).isEqualTo(2);
        assertThat(root.path("evaluations").path("page").path("totalElements").asLong()).isEqualTo(1);
        // The visible rows are the lists' rows: no drift possible by construction.
        assertThat(root.path("tasks").path("content").get(0).path("title").asText()).isNotEmpty();
        assertThat(root.path("internship").path("reference").asText()).isNotEmpty();
        // Unused local (keeps the fixture readable).
        assertThat(draft).isNotBlank();
        assertThat(completed).isNotNull();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private UUID createTask(String supervisorBearer, String title, String visibleFrom) throws Exception {
        String body = "{\"title\":\"" + title + "\""
                + (visibleFrom != null ? ",\"visibleFrom\":\"" + visibleFrom + "\"" : "") + "}";
        MvcResult created = mockMvc.perform(post("/api/internships/" + internshipId + "/tasks")
                        .header("Authorization", "Bearer " + supervisorBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
    }

    private void completeAsIntern(UUID taskId) throws Exception {
        mockMvc.perform(patch("/api/internships/tasks/" + taskId + "/status")
                        .header("Authorization", "Bearer " + internToken)
                        .param("status", "COMPLETED"))
                .andExpect(status().isOk());
    }

    private void reviewTask(boolean approve, UUID taskId) throws Exception {
        mockMvc.perform(post("/api/internships/tasks/" + taskId + "/review")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":" + approve + "}"))
                .andExpect(status().isOk());
    }

    private String createJournalEntry(String title, String description) throws Exception {
        return createJournalEntry(internToken, title, description);
    }

    private String createJournalEntry(String bearer, String title, String description) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/internships/" + internshipId + "/journal/entries")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"" + description + "\",\"entryDate\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    private void submitJournalEntry(String entryId) throws Exception {
        mockMvc.perform(post("/api/internships/journal/entries/" + entryId + "/submit")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());
    }

    private void validateJournalEntry(String entryId, String comment) throws Exception {
        mockMvc.perform(post("/api/internships/journal/entries/" + entryId + "/validate")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"" + comment + "\"}"))
                .andExpect(status().isOk());
    }

    private UUID uploadDeliverable(String title) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf",
                "%PDF-1.4 t13 sample".getBytes(StandardCharsets.UTF_8));
        MvcResult created = mockMvc.perform(multipart("/api/internships/" + internshipId + "/deliverables")
                        .file(file)
                        .param("title", title)
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
    }

    private void createAndSubmitDeliverable(String title) throws Exception {
        UUID id = uploadDeliverable(title);
        mockMvc.perform(post("/api/internships/deliverables/" + id + "/submit")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());
    }
}
