package tn.steg.backend.companion.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E9 — Companion (journal) workflow integration test.
 *
 * <p>Exercises the full journal lifecycle against a real Testcontainers PostgreSQL:
 * <ol>
 *   <li>Intern creates a journal entry (DRAFT).</li>
 *   <li>Intern submits the entry (SUBMITTED).</li>
 *   <li>Supervisor validates the entry (VALIDATED).</li>
 *   <li>Intern cannot validate their own entry (403).</li>
 *   <li>Supervisor cannot submit on behalf of the intern (403).</li>
 *   <li>Journal entry listing scoped to internship — other interns are excluded.</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("E9 — Journal/Companion workflow integration")
class JournalWorkflowIntegrationTest {

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
    private String supervisorToken;
    private String otherInternToken;
    private UUID internshipId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Create users
        User internUser = userRepository.saveAndFlush(new User("journal_intern_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));

        User supUser = userRepository.saveAndFlush(new User("journal_sup_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        supervisorToken = jwtService.generateAccessToken(supUser.getId(), supUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        User otherUser = userRepository.saveAndFlush(new User("journal_other_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        otherInternToken = jwtService.generateAccessToken(otherUser.getId(), otherUser.getEmail(), List.of("ROLE_INTERN"));

        // Create candidate + internship
        University uni = universityRepository.saveAndFlush(new University("UNI_J_" + suffix, "Journal Uni"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("JOU" + suffix).getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Journal", "Intern", internUser.getEmail(), cinHash, uni);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("JOU" + suffix);
        candidate = candidateRepository.saveAndFlush(candidate);

        Department dept = departmentRepository.saveAndFlush(new Department("DEPT_J_" + suffix, "Journal Dept", "JOU"));
        Employee supervisor = new Employee("EMP_J_" + suffix, "Journal", "Sup", dept);
        supervisor.setUser(supUser);
        supervisor = employeeRepository.saveAndFlush(supervisor);

        // Create and assign internship via service (gives it ACTIVE state with supervisor link)
        User hrUser = userRepository.saveAndFlush(new User("journal_hr_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        var hrPrincipal = new tn.steg.backend.common.domain.model.UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));
        String hrToken = jwtService.generateAccessToken(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));

        InternshipResponse internship = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.now().minusDays(30),
                LocalDate.now().plusDays(60),
                "Journal project",
                "Ingénieur",
                false), hrPrincipal);
        internshipId = internship.id();

        // Assign supervisor so internship becomes ACTIVE
        internshipService.assign(internshipId, new InternshipAssignmentRequest(
                dept.getId(), supervisor.getId(),
                LocalDate.now().minusDays(30), LocalDate.now().plusDays(60),
                "Journal supervisor assignment"), hrPrincipal);
    }

    @Test
    @DisplayName("J1: intern creates a DRAFT journal entry")
    void internCreatesDraftJournalEntry() throws Exception {
        String body = createJournalEntry(internToken, "Journée de travail", "Activités du jour");
        assertThat(body).contains("DRAFT");
        assertThat(body).contains("Journée de travail");
    }

    @Test
    @DisplayName("J2: intern submits a journal entry (DRAFT → SUBMITTED)")
    void internSubmitsJournalEntry() throws Exception {
        String created = createJournalEntry(internToken, "Journée soumise", "Description soumise");
        String journalId = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(post("/api/internships/journal/entries/" + journalId + "/submit")
                .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    @DisplayName("J3: supervisor validates a submitted entry (SUBMITTED → VALIDATED)")
    void supervisorValidatesJournalEntry() throws Exception {
        String created = createJournalEntry(internToken, "Entry to validate", "Details here");
        String journalId = objectMapper.readTree(created).get("id").asText();

        // Intern submits
        mockMvc.perform(post("/api/internships/journal/entries/" + journalId + "/submit")
                .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());

        // Supervisor validates
        mockMvc.perform(post("/api/internships/journal/entries/" + journalId + "/validate")
                .header("Authorization", "Bearer " + supervisorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Bien documenté.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    @Test
    @DisplayName("J4: intern cannot validate their own entry (403)")
    void internCannotValidateOwnEntry() throws Exception {
        String created = createJournalEntry(internToken, "Self-validate attempt", "...");
        String journalId = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(post("/api/internships/journal/entries/" + journalId + "/submit")
                .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/internships/journal/entries/" + journalId + "/validate")
                .header("Authorization", "Bearer " + internToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Self-approve\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("J5: supervisor cannot create a journal entry on behalf of intern (403)")
    void supervisorCannotCreateJournalEntry() throws Exception {
        mockMvc.perform(post("/api/internships/" + internshipId + "/journal/entries")
                .header("Authorization", "Bearer " + supervisorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Supervisor entry\",\"description\":\"Not allowed\",\"entryDate\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("J6: a different intern cannot view this internship's journal (403/404)")
    void otherInternCannotViewJournal() throws Exception {
        mockMvc.perform(get("/api/internships/" + internshipId + "/journal/entries")
                .header("Authorization", "Bearer " + otherInternToken))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status).isIn(403, 404);
                });
    }

    private String createJournalEntry(String token, String title, String description) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/internships/" + internshipId + "/journal/entries")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"description\":\"" + description + "\",\"entryDate\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getContentAsString();
    }
}
