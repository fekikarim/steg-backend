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
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.infrastructure.persistence.InternshipAssignmentRepository;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test (found by the live smoke run): a user-backed supervisor
 * with NO linked Employee row validating a journal entry or deliverable blew
 * up with a 500 — {@code Map.of} in the review audit payload rejects the
 * null {@code validatedBy}. The review must succeed and simply omit the key.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("Review audit tolerates a supervisor without Employee row")
class ReviewAuditNullSupervisorTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 review audit probe".getBytes(StandardCharsets.UTF_8);

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipAssignmentRepository assignmentRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private String internToken;
    private String supervisorToken;
    private UUID internshipId;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User internUser = userRepository.saveAndFlush(new User("review_intern_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));

        // Deliberately NO Employee row for this supervisor (seeded-demo shape).
        User supUser = userRepository.saveAndFlush(new User("review_sup_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        supervisorToken = jwtService.generateAccessToken(supUser.getId(), supUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        User adminUser = userRepository.saveAndFlush(new User("review_admin_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        UserPrincipal adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        University uni = universityRepository.saveAndFlush(new University("UNI_R_" + suffix, "Review Uni"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("REV" + suffix).getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Review", "Intern", internUser.getEmail(), cinHash, uni);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("REV" + suffix);
        candidate = candidateRepository.saveAndFlush(candidate);

        Department dept = departmentRepository.saveAndFlush(new Department("DEPT_R_" + suffix, "Review Dept", "REV"));

        InternshipResponse internship = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.now().minusDays(30),
                LocalDate.now().plusDays(60),
                "Review project",
                "Ingénieur",
                false), adminPrincipal);
        internshipId = internship.id();

        // User-backed ACTIVE assignment, legacy employee link left null.
        InternshipAssignment assignment = new InternshipAssignment(
                ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                        .findById(internshipId).orElseThrow(),
                dept, null, null,
                LocalDate.now().minusDays(30), LocalDate.now().minusDays(30), LocalDate.now().plusDays(60),
                AssignmentStatus.ACTIVE);
        assignment.setSupervisorUser(supUser);
        ((org.springframework.data.jpa.repository.JpaRepository<InternshipAssignment, UUID>) assignmentRepository)
                .saveAndFlush(assignment);
    }

    @Test
    @DisplayName("supervisor without Employee row validates a journal entry (no 500)")
    void bareSupervisorValidatesJournalEntry() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/internships/" + internshipId + "/journal/entries")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Entry\",\"description\":\"Details\",\"entryDate\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID journalId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        mockMvc.perform(post("/api/internships/journal/entries/" + journalId + "/submit")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/internships/journal/entries/" + journalId + "/validate")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Good work.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    @Test
    @DisplayName("supervisor without Employee row validates a deliverable (no 500)")
    void bareSupervisorValidatesDeliverable() throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/api/internships/" + internshipId + "/deliverables")
                        .file(new MockMultipartFile("file", "report.pdf", "application/pdf", PDF_BYTES))
                        .param("title", "Rapport de stage")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isCreated())
                .andReturn();
        UUID deliverableId = UUID.fromString(objectMapper.readTree(uploaded.getResponse().getContentAsString()).get("id").asText());
        mockMvc.perform(post("/api/internships/deliverables/" + deliverableId + "/submit")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/internships/deliverables/" + deliverableId + "/validate")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }
}
