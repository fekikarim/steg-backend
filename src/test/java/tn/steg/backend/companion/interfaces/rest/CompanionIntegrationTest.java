package tn.steg.backend.companion.interfaces.rest;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.comment.application.dto.CommentRequest;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.application.dto.JournalEntryRequest;
import tn.steg.backend.companion.application.dto.TaskRequest;
import tn.steg.backend.companion.application.dto.ValidationRequest;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.companion.infrastructure.persistence.DeliverableRepository;
import tn.steg.backend.companion.infrastructure.persistence.InternshipJournalRepository;
import tn.steg.backend.companion.infrastructure.persistence.JournalEntryRepository;
import tn.steg.backend.companion.infrastructure.persistence.TaskRepository;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.infrastructure.persistence.InternshipAssignmentRepository;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("Companion & Comments Integration Tests")
class CompanionIntegrationTest {

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
    private InternshipAssignmentRepository assignmentRepository;

    @Autowired
    private InternshipJournalRepository journalRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private DeliverableRepository deliverableRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private InternshipService internshipService;

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;

    private User adminUser;
    private String adminToken;

    private Candidate internCandidate;
    private User internUser;
    private String internToken;

    private Candidate otherCandidate;
    private User otherUser;
    private String otherToken;

    private Employee supervisorEmployee;
    private User supervisorUser;
    private String supervisorToken;

    private Employee otherSupervisorEmployee;
    private User otherSupervisorUser;
    private String otherSupervisorToken;

    private Internship internship;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // 1. Admin
        adminUser = userRepository.saveAndFlush(new User("admin_comp@steg.com", "hash", UserStatus.ACTIVE));
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        // 2. Intern Candidate
        University uni = universityRepository.saveAndFlush(new University("INSAT_COMP", "INSAT"));
        internUser = userRepository.saveAndFlush(new User("intern_comp@steg.com", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(), List.of("ROLE_CANDIDATE"));

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest("11223344".getBytes(StandardCharsets.UTF_8)));
        internCandidate = new Candidate("Ali", "Ben Salah", "intern_comp@steg.com", cinHash, uni);
        internCandidate.setUser(internUser);
        internCandidate.setNationalIdEncrypted("11223344");
        internCandidate = candidateRepository.saveAndFlush(internCandidate);

        // 3. Other Candidate
        otherUser = userRepository.saveAndFlush(new User("other_comp@steg.com", "hash", UserStatus.ACTIVE));
        otherToken = jwtService.generateAccessToken(otherUser.getId(), otherUser.getEmail(), List.of("ROLE_CANDIDATE"));
        String otherCinHash = Base64.getEncoder().encodeToString(digest.digest("99887766".getBytes(StandardCharsets.UTF_8)));
        otherCandidate = new Candidate("Other", "Candidate", "other_comp@steg.com", otherCinHash, uni);
        otherCandidate.setUser(otherUser);
        otherCandidate.setNationalIdEncrypted("99887766");
        otherCandidate = candidateRepository.saveAndFlush(otherCandidate);

        // 4. Department & Supervisors
        Department dept = departmentRepository.saveAndFlush(new Department("IT_DEV", "IT Development", "IT Department"));

        supervisorUser = userRepository.saveAndFlush(new User("sup_comp@steg.com", "hash", UserStatus.ACTIVE));
        supervisorToken = jwtService.generateAccessToken(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));
        supervisorEmployee = new Employee("EMP-COMP-1", "Moncef", "Trabelsi", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        otherSupervisorUser = userRepository.saveAndFlush(new User("other_sup@steg.com", "hash", UserStatus.ACTIVE));
        otherSupervisorToken = jwtService.generateAccessToken(otherSupervisorUser.getId(), otherSupervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));
        otherSupervisorEmployee = new Employee("EMP-COMP-2", "Nabil", "Gharbi", dept);
        otherSupervisorEmployee.setUser(otherSupervisorUser);
        otherSupervisorEmployee = employeeRepository.saveAndFlush(otherSupervisorEmployee);

        // 5. Create Internship via Service (tests journal auto-creation)
        InternshipCreateManualRequest createRequest = new InternshipCreateManualRequest(
                internCandidate.getId(),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31),
                "Companion Project",
                "Ingénieur",
                false
        );
        UserPrincipal adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        InternshipResponse created = internshipService.createManual(createRequest, adminPrincipal);

        internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();

        // 6. Assign Supervisor
        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31), "Project assignment"
        ), adminPrincipal);
    }

    @Test
    @DisplayName("Internship creation automatically creates exactly 1 InternshipJournal")
    void internshipAutoCreatesJournal() {
        var journalOpt = journalRepository.findByInternshipId(internship.getId());
        assertThat(journalOpt).isPresent();
        assertThat(journalOpt.get().getInternship().getId()).isEqualTo(internship.getId());
    }

    @Test
    @DisplayName("Tasks: CRUD, update status, and pagination")
    void taskLifecycle() throws Exception {
        TaskRequest request = new TaskRequest("Write Architecture Specs", "First draft of specs", internUser.getId(), LocalDate.now().plusDays(5), TaskStatus.TODO);

        // Create task
        String response = mockMvc.perform(post("/api/internships/" + internship.getId() + "/tasks")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Write Architecture Specs"))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andReturn().getResponse().getContentAsString();

        JsonNode jsonNode = objectMapper.readTree(response);
        UUID taskId = UUID.fromString(jsonNode.get("id").asText());

        // Update task status to COMPLETED
        mockMvc.perform(patch("/api/internships/tasks/" + taskId + "/status?status=COMPLETED")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        // List tasks
        mockMvc.perform(get("/api/internships/" + internship.getId() + "/tasks")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("Journal Entry: Draft -> Submit -> Supervisor Validate & IDOR checks")
    void journalEntryLifecycle() throws Exception {
        JournalEntryRequest request = new JournalEntryRequest("Day 1: Setup", "Installed tooling and configured DB", LocalDate.now());

        // 1. Intern creates entry (DRAFT)
        String res = mockMvc.perform(post("/api/internships/" + internship.getId() + "/journal/entries")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();

        UUID entryId = UUID.fromString(objectMapper.readTree(res).get("id").asText());

        // 2. Unassigned supervisor tries to validate DRAFT directly -> fails (invalid transition + not authorized)
        mockMvc.perform(post("/api/internships/journal/entries/" + entryId + "/validate")
                        .header("Authorization", "Bearer " + otherSupervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ValidationRequest("Great work"))))
                .andExpect(status().isForbidden());

        // 3. Supervisor tries to validate DRAFT -> fails with 422 UNPROCESSABLE_ENTITY (only SUBMITTED can be validated)
        mockMvc.perform(post("/api/internships/journal/entries/" + entryId + "/validate")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ValidationRequest("Too early"))))
                .andExpect(status().isUnprocessableEntity());

        // 4. Intern submits entry
        mockMvc.perform(post("/api/internships/journal/entries/" + entryId + "/submit")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // 5. Other intern tries to submit/view -> 403 Forbidden
        mockMvc.perform(post("/api/internships/journal/entries/" + entryId + "/submit")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());

        // 6. Assigned supervisor validates entry
        mockMvc.perform(post("/api/internships/journal/entries/" + entryId + "/validate")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ValidationRequest("Approved!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.validatedByName").value("Moncef Trabelsi"));
    }

    @Test
    @DisplayName("Deliverables: v1 create -> v2 upload -> submit -> validate -> download & comments")
    void deliverableLifecycleAndComments() throws Exception {
        // 1. Create v1 deliverable (PDF)
        MockMultipartFile fileV1 = new MockMultipartFile(
                "file",
                "final_report_v1.pdf",
                "application/pdf",
                "%PDF-1.4 sample pdf content v1".getBytes(StandardCharsets.UTF_8)
        );

        String delivRes = mockMvc.perform(multipart("/api/internships/" + internship.getId() + "/deliverables")
                        .file(fileV1)
                        .param("title", "Final Internship Report")
                        .param("description", "Comprehensive report")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Final Internship Report"))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();

        UUID deliverableId = UUID.fromString(objectMapper.readTree(delivRes).get("id").asText());

        // 2. Upload v2
        MockMultipartFile fileV2 = new MockMultipartFile(
                "file",
                "final_report_v2.pdf",
                "application/pdf",
                "%PDF-1.4 sample pdf content v2 with corrections".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/internships/deliverables/" + deliverableId + "/versions")
                        .file(fileV2)
                        .param("changeSummary", "Fixed typos and added conclusion")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.latestVersion.versionNumber").value(2))
                .andExpect(jsonPath("$.latestVersion.changeSummary").value("Fixed typos and added conclusion"));

        // 3. Check versions list
        mockMvc.perform(get("/api/internships/deliverables/" + deliverableId + "/versions")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 4. Submit & Validate
        mockMvc.perform(post("/api/internships/deliverables/" + deliverableId + "/submit")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        mockMvc.perform(post("/api/internships/deliverables/" + deliverableId + "/validate")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ValidationRequest("Excellent work!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));

        // 5. Download deliverable v2
        mockMvc.perform(get("/api/internships/deliverables/" + deliverableId + "/download")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"final_report_v2.pdf\""))
                .andExpect(content().string("%PDF-1.4 sample pdf content v2 with corrections"));

        // 6. Comments on Deliverable
        CommentRequest commentReq = new CommentRequest("Please double check section 3");
        mockMvc.perform(post("/api/deliverables/" + deliverableId + "/comments")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(commentReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("Please double check section 3"))
                .andExpect(jsonPath("$.authorEmail").value("sup_comp@steg.com"));

        mockMvc.perform(get("/api/deliverables/" + deliverableId + "/comments")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("Please double check section 3"));

        // 7. Non-participant trying to comment -> 403 Forbidden
        mockMvc.perform(post("/api/deliverables/" + deliverableId + "/comments")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(commentReq)))
                .andExpect(status().isForbidden());
    }
}
