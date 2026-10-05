package tn.steg.backend.internship.application;

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
import tn.steg.backend.internship.application.dto.InternshipCreateFromApplicationRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.infrastructure.persistence.InternshipAssignmentRepository;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.workflow.application.WorkflowService;
import tn.steg.backend.workflow.application.dto.ApplicationApprovalRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("Always-a-Supervisor Policy Integration Tests")
class InternshipAlwaysHasSupervisorTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CandidateRepository candidateRepository;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private InternshipRepository internshipRepository;

    @Autowired
    private InternshipAssignmentRepository assignmentRepository;

    @Autowired
    private InternshipApplicationRepository applicationRepository;

    @Autowired
    private WorkflowService workflowService;

    private MockMvc mockMvc;

    private User adminUser;
    private String adminToken;

    private User supervisorUser;
    private Department department;
    private Candidate candidate;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Role adminRole = roleRepository.findByCode("ADMIN").orElse(null);
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElse(null);

        adminUser = userRepository.saveAndFlush(new User("admin_sup_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        if (adminRole != null) adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorUser = userRepository.saveAndFlush(new User("sup_policy_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        if (supervisorRole != null) supervisorUser.getAssignedRoles().add(supervisorRole);
        supervisorUser = userRepository.saveAndFlush(supervisorUser);

        department = departmentRepository.saveAndFlush(new Department("DEPT_SUP_" + suffix, "Dept Sup", "IT"));
        University university = universityRepository.saveAndFlush(new University("UNI_SUP_" + suffix, "Uni Sup"));

        User candUser = userRepository.saveAndFlush(new User("cand_sup_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(suffix.getBytes(StandardCharsets.UTF_8)));
        candidate = new Candidate("Cand", "Test", candUser.getEmail(), cinHash, university);
        candidate.setUser(candUser);
        candidate = candidateRepository.saveAndFlush(candidate);
    }

    @Test
    @DisplayName("Manual creation without supervisor defaults supervisor to acting Admin and creates active assignment")
    void manualCreationWithoutSupervisorDefaultsToActingAdmin() throws Exception {
        InternshipCreateManualRequest request = new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.now(), LocalDate.now().plusMonths(1),
                "Manual Subject", "Licence", false, null
        );

        String response = mockMvc.perform(post("/api/internships/manual")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        UUID internshipId = UUID.fromString(objectMapper.readTree(response).get("id").asText());
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(internshipId).orElseThrow();

        assertThat(internship.getSupervisorUser()).isNotNull();
        assertThat(internship.getSupervisorUser().getId()).isEqualTo(adminUser.getId());
    }

    @Test
    @DisplayName("Manual creation with explicit supervisor assigns chosen supervisor")
    void manualCreationWithExplicitSupervisorAssignsSupervisor() throws Exception {
        InternshipCreateManualRequest request = new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.now(), LocalDate.now().plusMonths(1),
                "Manual Subject", "Licence", false, supervisorUser.getId()
        );

        String response = mockMvc.perform(post("/api/internships/manual")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID internshipId = UUID.fromString(objectMapper.readTree(response).get("id").asText());
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(internshipId).orElseThrow();

        assertThat(internship.getSupervisorUser()).isNotNull();
        assertThat(internship.getSupervisorUser().getId()).isEqualTo(supervisorUser.getId());
    }

    @Test
    @DisplayName("Creation from application without supervisor defaults to acting Admin")
    void creationFromApplicationDefaultsToActingAdmin() throws Exception {
        InternshipApplication app = new InternshipApplication("APP-TEST-FROM-APP", candidate, ApplicationStatus.APPROVED);
        app.setDesiredStartDate(LocalDate.now());
        app.setDesiredEndDate(LocalDate.now().plusMonths(1));
        app = applicationRepository.saveAndFlush(app);

        InternshipCreateFromApplicationRequest request = new InternshipCreateFromApplicationRequest(
                app.getId(), false, null
        );

        String response = mockMvc.perform(post("/api/internships/from-application")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID internshipId = UUID.fromString(objectMapper.readTree(response).get("id").asText());
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(internshipId).orElseThrow();

        assertThat(internship.getSupervisorUser()).isNotNull();
        assertThat(internship.getSupervisorUser().getId()).isEqualTo(adminUser.getId());
    }

    @Test
    @DisplayName("Application approval without supervisor defaults supervisor to acting Admin and creates active assignment")
    void approvalWithoutSupervisorDefaultsToActingAdmin() throws Exception {
        InternshipApplication app = new InternshipApplication("APP-TEST-APPROVE-DEF", candidate, ApplicationStatus.SUBMITTED);
        app.setDesiredStartDate(LocalDate.now());
        app.setDesiredEndDate(LocalDate.now().plusMonths(1));
        app = applicationRepository.saveAndFlush(app);
        workflowService.spawnApplicationWorkflow(app);

        // Advance to UNDER_REVIEW
        app.setStatus(ApplicationStatus.UNDER_REVIEW);
        app = applicationRepository.saveAndFlush(app);

        ApplicationApprovalRequest request = new ApplicationApprovalRequest(null, department.getId());

        mockMvc.perform(post("/api/applications/" + app.getId() + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supervisorUserId").value(adminUser.getId().toString()))
                .andExpect(jsonPath("$.internship.supervisorUserId").value(adminUser.getId().toString()));
    }

    @Test
    @DisplayName("Application approval with explicit supervisor assigns chosen supervisor")
    void approvalWithExplicitSupervisorAssignsSupervisor() throws Exception {
        InternshipApplication app = new InternshipApplication("APP-TEST-APPROVE-SUP", candidate, ApplicationStatus.SUBMITTED);
        app.setDesiredStartDate(LocalDate.now());
        app.setDesiredEndDate(LocalDate.now().plusMonths(1));
        app = applicationRepository.saveAndFlush(app);
        workflowService.spawnApplicationWorkflow(app);

        // Advance to UNDER_REVIEW
        app.setStatus(ApplicationStatus.UNDER_REVIEW);
        app = applicationRepository.saveAndFlush(app);

        ApplicationApprovalRequest request = new ApplicationApprovalRequest(supervisorUser.getId(), department.getId());

        mockMvc.perform(post("/api/applications/" + app.getId() + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supervisorUserId").value(supervisorUser.getId().toString()))
                .andExpect(jsonPath("$.internship.supervisorUserId").value(supervisorUser.getId().toString()));
    }
}
