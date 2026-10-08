package tn.steg.backend.internship.interfaces.rest;

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
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
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
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T12/B2 — {@code GET /api/internships/supervised} (Testcontainers).
 *
 * <p>Scope is always the caller's own supervised internships
 * ({@code assignedInternships}), ADMIN included (D1b): unlike
 * {@code GET /api/internships} — global for ADMIN — this endpoint never
 * leaks another supervisor's students onto the device. Counts mirror the
 * supervisor's own reads (staff task view, done = APPROVED only).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("T12 — Supervised internships list (own scope + aggregates)")
class SupervisedInternshipsTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;

    private String supAToken;
    private String supBToken;
    private String adminToken;
    private String internToken;
    private UUID internshipA1;
    private UUID internshipA2;
    private UUID internshipB1;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User supA = userRepository.saveAndFlush(new User("t12_supA_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        supAToken = jwtService.generateAccessToken(supA.getId(), supA.getEmail(), List.of("ROLE_SUPERVISOR"));

        User supB = userRepository.saveAndFlush(new User("t12_supB_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        supBToken = jwtService.generateAccessToken(supB.getId(), supB.getEmail(), List.of("ROLE_SUPERVISOR"));

        User admin = userRepository.saveAndFlush(new User("t12_admin_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        adminToken = jwtService.generateAccessToken(admin.getId(), admin.getEmail(), List.of("ROLE_ADMIN"));

        User internA1 = userRepository.saveAndFlush(new User("t12_iA1_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internA1.getId(), internA1.getEmail(), List.of("ROLE_INTERN"));

        University uni = universityRepository.saveAndFlush(new University("UNI_T12_" + suffix, "T12 Uni"));
        Department dept = departmentRepository.saveAndFlush(new Department("DEPT_T12_" + suffix, "T12 Direction", "T12"));

        Employee empA = employeeRepository.saveAndFlush(new Employee("EMP_T12A_" + suffix, "Sana", "A", dept));
        empA.setUser(supA);
        empA = employeeRepository.saveAndFlush(empA);
        Employee empB = employeeRepository.saveAndFlush(new Employee("EMP_T12B_" + suffix, "Karim", "B", dept));
        empB.setUser(supB);
        employeeRepository.saveAndFlush(empB);

        var hrPrincipal = new UserPrincipal(admin.getId(), admin.getEmail(), List.of("ROLE_ADMIN"));

        internshipA1 = newInternship(internA1, uni, dept, empA, hrPrincipal, suffix + "A1", "T12", "Alpha");
        User internA2 = userRepository.saveAndFlush(new User("t12_iA2_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        internshipA2 = newInternship(internA2, uni, dept, empA, hrPrincipal, suffix + "A2", "T12", "Beta");
        User internB1 = userRepository.saveAndFlush(new User("t12_iB1_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        internshipB1 = newInternship(internB1, uni, dept, empB, hrPrincipal, suffix + "B1", "T12", "Gamma");

        // D1: the ADMIN directly supervises A2 as well (single account, single role).
        Internship a2 = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(internshipA2).orElseThrow();
        a2.setSupervisorUser(admin);
        internshipRepository.saveAndFlush(a2);
    }

    private UUID newInternship(User intern, University uni, Department dept, Employee sup,
                               UserPrincipal hr, String cin, String first, String last) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(cin.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate(first, last, intern.getEmail(), cinHash, uni);
        candidate.setUser(intern);
        candidate.setNationalIdEncrypted(cin);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse internship = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.now().minusDays(30),
                LocalDate.now().plusDays(60),
                "T12 project",
                "Ingénieur",
                false), hr);
        internshipService.assign(internship.id(), new InternshipAssignmentRequest(
                dept.getId(), sup.getId(),
                LocalDate.now().minusDays(30), LocalDate.now().plusDays(60),
                "T12 assignment"), hr);
        return internship.id();
    }

    @Test
    @DisplayName("S1: a supervisor sees exactly his own students, nothing else")
    void supervisorSeesOwnStudentsOnly() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/internships/supervised")
                        .header("Authorization", "Bearer " + supAToken))
                .andExpect(status().isOk())
                .andReturn();
        String payload = result.getResponse().getContentAsString();
        assertThat(payload).contains(internshipA1.toString());
        assertThat(payload).contains(internshipA2.toString());
        assertThat(payload).doesNotContain(internshipB1.toString());
    }

    @Test
    @DisplayName("S2: an ADMIN sees only his own supervised students here (D1b), the global list elsewhere")
    void adminSeesOwnSupervisedOnly() throws Exception {
        // Own-scope endpoint: only A2 (directly supervised by the ADMIN).
        String own = mockMvc.perform(get("/api/internships/supervised")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(own).contains(internshipA2.toString());
        assertThat(own).doesNotContain(internshipA1.toString());
        assertThat(own).doesNotContain(internshipB1.toString());

        // Global endpoint keeps ADMIN's global view (unchanged contract).
        String global = mockMvc.perform(get("/api/internships")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(global).contains(internshipA1.toString());
        assertThat(global).contains(internshipB1.toString());
    }

    @Test
    @DisplayName("S3: interns and anonymous callers are refused (403/401)")
    void internAndAnonymousRefused() throws Exception {
        mockMvc.perform(get("/api/internships/supervised")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/internships/supervised"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S4: a foreign internship detail is 404 (no leak)")
    void foreignDetailIsNotFound() throws Exception {
        mockMvc.perform(get("/api/internships/" + internshipB1)
                        .header("Authorization", "Bearer " + supAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("S5: aggregates mirror the supervisor's own reads")
    void aggregatesMatchUnderlyingData() throws Exception {
        // Tasks: 1 APPROVED + 1 COMPLETED + 1 TODO + 1 hidden scheduled.
        UUID tApproved = createTask(supAToken, internshipA1, "Approved work", null);
        UUID tCompleted = createTask(supAToken, internshipA1, "Done awaiting review", null);
        createTask(supAToken, internshipA1, "Fresh todo", null);
        createTask(supAToken, internshipA1, "Future scheduled",
                Instant.now().plusSeconds(30 * 24 * 3600).toString());
        completeAsIntern(tApproved);
        completeAsIntern(tCompleted);
        reviewTask(supAToken, tApproved, true, null);

        // Journal: 1 draft + 1 submitted + 1 validated (only 2 pending).
        createJournalEntry(internToken, internshipA1, "Draft day", "work");
        String submitted = createJournalEntry(internToken, internshipA1, "Submitted day", "work");
        submitJournalEntry(internToken, submitted);
        String validated = createJournalEntry(internToken, internshipA1, "Validated day", "work");
        submitJournalEntry(internToken, validated);
        validateJournalEntry(supAToken, validated, "Bien.");

        // Deliverables: 1 submitted (pending) + 1 draft.
        createAndSubmitDeliverable(internToken, internshipA1, "Report Submitted");
        createDraftDeliverable(internToken, internshipA1, "Report Draft");

        // Evaluations: 1 weekly.
        mockMvc.perform(post("/api/internships/" + internshipA1 + "/evaluations")
                        .header("Authorization", "Bearer " + supAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"WEEKLY\",\"evaluationDate\":\"" + LocalDate.now() + "\",\"feedback\":\"Bon travail.\"}"))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/internships/supervised")
                        .header("Authorization", "Bearer " + supAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.internshipId == '" + internshipA1 + "')].tasksTotal").value(contains(4)))
                .andExpect(jsonPath("$[?(@.internshipId == '" + internshipA1 + "')].tasksCompleted").value(contains(1)))
                .andExpect(jsonPath("$[?(@.internshipId == '" + internshipA1 + "')].pendingJournal").value(contains(2)))
                .andExpect(jsonPath("$[?(@.internshipId == '" + internshipA1 + "')].submittedJournal").value(contains(1)))
                .andExpect(jsonPath("$[?(@.internshipId == '" + internshipA1 + "')].pendingDeliverables").value(contains(1)))
                .andExpect(jsonPath("$[?(@.internshipId == '" + internshipA1 + "')].evaluationsCount").value(contains(1)))
                .andExpect(jsonPath("$[?(@.internshipId == '" + internshipA1 + "')].departmentName").value(contains("T12 Direction")))
                .andExpect(jsonPath("$[?(@.internshipId == '" + internshipA1 + "')].internName").value(contains("T12 Alpha")))
                .andExpect(jsonPath("$[?(@.internshipId == '" + internshipA1 + "')].reference").isNotEmpty())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains(internshipA1.toString());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private UUID createTask(String supervisorBearer, UUID internshipId, String title, String visibleFrom) throws Exception {
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

    private void reviewTask(String supervisorBearer, UUID taskId, boolean approve, String comment) throws Exception {
        mockMvc.perform(post("/api/internships/tasks/" + taskId + "/review")
                        .header("Authorization", "Bearer " + supervisorBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":" + approve + ",\"comment\":" + (comment == null ? "null" : "\"" + comment + "\"") + "}"))
                .andExpect(status().isOk());
    }

    private String createJournalEntry(String bearer, UUID internshipId, String title, String description) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/internships/" + internshipId + "/journal/entries")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"" + description + "\",\"entryDate\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    private void submitJournalEntry(String bearer, String entryId) throws Exception {
        mockMvc.perform(post("/api/internships/journal/entries/" + entryId + "/submit")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk());
    }

    private void validateJournalEntry(String bearer, String entryId, String comment) throws Exception {
        mockMvc.perform(post("/api/internships/journal/entries/" + entryId + "/validate")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"" + comment + "\"}"))
                .andExpect(status().isOk());
    }

    private UUID uploadDeliverable(String bearer, UUID internshipId, String title) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf",
                "%PDF-1.4 t12 sample".getBytes(StandardCharsets.UTF_8));
        MvcResult created = mockMvc.perform(multipart("/api/internships/" + internshipId + "/deliverables")
                        .file(file)
                        .param("title", title)
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
    }

    private void createAndSubmitDeliverable(String bearer, UUID internshipId, String title) throws Exception {
        UUID id = uploadDeliverable(bearer, internshipId, title);
        mockMvc.perform(post("/api/internships/deliverables/" + id + "/submit")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk());
    }

    private void createDraftDeliverable(String bearer, UUID internshipId, String title) throws Exception {
        uploadDeliverable(bearer, internshipId, title);
    }
}
