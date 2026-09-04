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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
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
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateFromApplicationRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipUpdateDatesRequest;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
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
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("Internship & Assignment Integration Tests")
class InternshipIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CandidateRepository candidateRepository;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private InternshipApplicationRepository applicationRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private InternshipRepository internshipRepository;

    @Autowired
    private InternshipAssignmentRepository assignmentRepository;

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;
    private String adminToken;
    private Department department;
    private Employee supervisor;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        User adminUser = new User("admin_intern@steg.com", "hash", UserStatus.ACTIVE);
        adminUser = userRepository.saveAndFlush(adminUser);

        adminToken = jwtService.generateAccessToken(
                adminUser.getId(),
                adminUser.getEmail(),
                List.of("ROLE_ADMIN")
        );

        department = new Department("DIST_NORD", "Distribution Nord", "Distribution Departement Nord");
        department = departmentRepository.saveAndFlush(department);

        supervisor = new Employee("EMP-9901", "Salah", "Mejri", department);
        supervisor = employeeRepository.saveAndFlush(supervisor);
    }

    private Candidate createCandidate(String email, String cin) throws Exception {
        University uni = universityRepository.saveAndFlush(new University("UNI_" + UUID.randomUUID().toString().substring(0, 5), "Uni"));
        User user = userRepository.saveAndFlush(new User(email, "pass", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String hash = Base64.getEncoder().encodeToString(digest.digest(cin.getBytes(StandardCharsets.UTF_8)));

        Candidate candidate = new Candidate("Candidate", "One", email, hash, uni);
        candidate.setUser(user);
        candidate.setNationalIdEncrypted(cin);
        return candidateRepository.saveAndFlush(candidate);
    }

    @Test
    @DisplayName("Create from ACCEPTED Application: sets reference INT-YYYY-NNNNN and computes classification")
    void createFromAcceptedApplication() throws Exception {
        Candidate candidate = createCandidate("accepted_cand@steg.com", "12341234");

        InternshipApplication app = new InternshipApplication();
        app.setReference("APP-2026-99999");
        app.setCandidate(candidate);
        app.setStatus(ApplicationStatus.ACCEPTED);
        app.setDesiredStartDate(LocalDate.of(2026, 7, 1));
        app.setDesiredEndDate(LocalDate.of(2026, 8, 31)); // 2 months -> PERFECTIONNEMENT
        app.setProposedTheme("Smart Grid Automation");
        app = applicationRepository.saveAndFlush(app);

        InternshipCreateFromApplicationRequest request = new InternshipCreateFromApplicationRequest(
                app.getId(),
                null
        );

        mockMvc.perform(post("/api/internships/from-application")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value(matchesPattern("^INT-\\d{4}-\\d{5}$")))
                .andExpect(jsonPath("$.type").value("PERFECTIONNEMENT"))
                .andExpect(jsonPath("$.requirement").value("OBLIGATOIRE"))
                .andExpect(jsonPath("$.paymentEligible").value(true))
                .andExpect(jsonPath("$.status").value("PLANNED"));
    }

    @Test
    @DisplayName("Assignment and Atomic Reassignment: exactly one ACTIVE assignment at all times")
    void assignmentAndAtomicReassignment() throws Exception {
        Candidate candidate = createCandidate("assign_cand@steg.com", "88776655");

        InternshipCreateManualRequest createRequest = new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                "Observing Relay Stations",
                "Licence 2",
                false
        );

        String createResp = mockMvc.perform(post("/api/internships/manual")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("OBSERVATION"))
                .andExpect(jsonPath("$.paymentEligible").value(false))
                .andReturn().getResponse().getContentAsString();

        JsonNode createdJson = objectMapper.readTree(createResp);
        UUID internshipId = UUID.fromString(createdJson.get("id").asText());

        // 1. First assignment
        InternshipAssignmentRequest assignReq1 = new InternshipAssignmentRequest(
                department.getId(),
                supervisor.getId(),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                "Initial assignment"
        );

        mockMvc.perform(post("/api/internships/" + internshipId + "/assignments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignReq1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 2. Second assignment (reassignment) to another department/supervisor
        Department dept2 = departmentRepository.saveAndFlush(new Department("DIST_SUD", "Distribution Sud", "Departement Sud"));
        Employee sup2 = employeeRepository.saveAndFlush(new Employee("EMP-9902", "Ali", "Gharbi", dept2));

        InternshipAssignmentRequest assignReq2 = new InternshipAssignmentRequest(
                dept2.getId(),
                sup2.getId(),
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 7, 31),
                "Reassigned to Sud"
        );

        mockMvc.perform(post("/api/internships/" + internshipId + "/assignments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignReq2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.departmentName").value("Distribution Sud"));

        // 3. Verify history and invariant: exactly 1 ACTIVE assignment
        List<InternshipAssignment> assignments = assignmentRepository.findByInternshipId(internshipId);
        assertThat(assignments).hasSize(2);

        long activeCount = assignments.stream().filter(a -> a.getStatus() == AssignmentStatus.ACTIVE).count();
        long reassignedCount = assignments.stream().filter(a -> a.getStatus() == AssignmentStatus.REASSIGNED).count();

        assertThat(activeCount).isEqualTo(1);
        assertThat(reassignedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Classification transparency endpoint: returns applied rule description")
    void classificationTransparency() throws Exception {
        Candidate candidate = createCandidate("classif_cand@steg.com", "99112233");

        InternshipCreateManualRequest manualRequest = new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 6, 30), // 5 months -> PFE
                "PFE Machine Learning for Power Grid",
                "Master 2",
                null
        );

        String createResp = mockMvc.perform(post("/api/internships/manual")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manualRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID internshipId = UUID.fromString(objectMapper.readTree(createResp).get("id").asText());

        mockMvc.perform(get("/api/internships/" + internshipId + "/classification")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PFE"))
                .andExpect(jsonPath("$.requirement").value("OBLIGATOIRE"))
                .andExpect(jsonPath("$.paymentEligible").value(true))
                .andExpect(jsonPath("$.appliedRuleDescription").value(org.hamcrest.Matchers.containsString("PFE")));
    }
}
