package tn.steg.backend.e2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.application.dto.AuthResponse;
import tn.steg.backend.iam.application.dto.ChangePasswordRequest;
import tn.steg.backend.iam.application.dto.LoginRequest;
import tn.steg.backend.iam.application.dto.ResetAccountPasswordResponse;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.SupervisorManagementService;
import tn.steg.backend.internship.application.dto.CreateSupervisorRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;

/**
 * E2 — E2E Auth, temporary password lifecycle, and candidate scoping tests.
 *
 * <p>Proves:
 * 1. Newly created supervisors receive temporary passwords with mustChangePassword=true
 * 2. Login succeeds with temporary credentials and flags mustChangePassword
 * 3. Change password requires robust rules and clears mustChangePassword
 * 4. After change, temporary password is invalidated and new password issues mustChangePassword=false
 * 5. Candidate scoping via REST proves supervisor sees only own candidates and gets 404 for unassigned
 * 6. Admin retains global access across all endpoints and candidates
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("E2 — Auth, password lifecycle, and supervisor scoping integration tests")
class AuthPasswordIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private SupervisorManagementService supervisorManagementService;
    @Autowired private JwtService jwtService;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private UserPrincipal adminPrincipal;
    private University university;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Role adminRole = roleRepository.findByCode("ADMIN").orElse(null);
        adminUser = new User("admin_auth_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE);
        if (adminRole != null) {
            adminUser.getAssignedRoles().add(adminRole);
        }
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        departmentRepository.saveAndFlush(new Department("DEPT_AUTH_" + suffix, "Dept Auth", "IT"));
        university = universityRepository.saveAndFlush(new University("UNI_AUTH_" + suffix, "Uni Auth"));
    }

    @Test
    @DisplayName("Supervisor account creation, login with mustChangePassword=true, password change, and re-login")
    void supervisorCreationAndLifecycleAuthFlow() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String supervisorEmail = "sup_flow_" + suffix + "@steg.tn";

        // 1. Admin creates supervisor account — response contains the one-time password
        ResetAccountPasswordResponse createResponse = supervisorManagementService.createSupervisor(
                new CreateSupervisorRequest(supervisorEmail), adminPrincipal);
        String tempPassword = createResponse.temporaryPassword();
        assertThat(tempPassword).isNotBlank().hasSize(20);

        // 2. Login with bad password fails with 422 AUTHENTICATION_FAILED
        LoginRequest badLogin = new LoginRequest(supervisorEmail, "WrongPassword123!");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badLogin)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("AUTHENTICATION_FAILED"));

        // 3. Login with temporary password succeeds and mustChangePassword is true
        LoginRequest initialLogin = new LoginRequest(supervisorEmail, tempPassword);
        String loginResponseJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initialLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        AuthResponse authRes = objectMapper.readValue(loginResponseJson, AuthResponse.class);
        String supervisorTempToken = authRes.getAccessToken();
        assertThat(authRes.isMustChangePassword()).isTrue();

        // 3.5. Try to access a protected endpoint before changing password -> 403 PASSWORD_CHANGE_REQUIRED
        mockMvc.perform(get("/api/supervisors")
                        .header("Authorization", "Bearer " + supervisorTempToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("PASSWORD_CHANGE_REQUIRED"));

        // 4. Change password with short/invalid password fails with 400
        ChangePasswordRequest invalidRequest = new ChangePasswordRequest(tempPassword, "short");
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + supervisorTempToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());

        // 5. Change password with valid secure password succeeds with 204
        String newPassword = "NewValidSecurePassword123!";
        ChangePasswordRequest validRequest = new ChangePasswordRequest(tempPassword, newPassword);
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + supervisorTempToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isNoContent());

        // 6. Old temporary password no longer works
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initialLogin)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("AUTHENTICATION_FAILED"));

        // 7. Login with new password succeeds and mustChangePassword is false
        LoginRequest newLogin = new LoginRequest(supervisorEmail, newPassword);
        String secondLoginJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        AuthResponse secondAuthRes = objectMapper.readValue(secondLoginJson, AuthResponse.class);
        assertThat(secondAuthRes.isMustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("Supervisor candidate scoping: only assigned candidate visible, unassigned candidate returns 404")
    void supervisorScopingAndCandidateIsolation() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String supervisorEmail = "sup_scope_" + suffix + "@steg.tn";

        // 1. Create supervisor and use the returned one-time password
        ResetAccountPasswordResponse createResp = supervisorManagementService.createSupervisor(
                new CreateSupervisorRequest(supervisorEmail), adminPrincipal);
        String tempPassword = createResp.temporaryPassword();
        assertThat(tempPassword).isNotBlank();

        User supervisorUser = userRepository.findByEmail(supervisorEmail).orElseThrow();

        String newPassword = "SupervisorSecurePassword123!";
        String initialLoginJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(supervisorEmail, tempPassword))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tempToken = objectMapper.readValue(initialLoginJson, AuthResponse.class).getAccessToken();

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + tempToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePasswordRequest(tempPassword, newPassword))))
                .andExpect(status().isNoContent());

        String loginJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(supervisorEmail, newPassword))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String supervisorToken = objectMapper.readValue(loginJson, AuthResponse.class).getAccessToken();

        // 2. Create Candidate 1 and Candidate 2
        Candidate cand1 = createCandidate("Cand1_" + suffix);
        Candidate cand2 = createCandidate("Cand2_" + suffix);

        // 3. Assign Candidate 1 to this supervisor via manual internship creation
        InternshipCreateManualRequest req = new InternshipCreateManualRequest(
                cand1.getId(), LocalDate.now(), LocalDate.now().plusMonths(2),
                "Scoping Project", "Master", false, supervisorUser.getId()
        );
        mockMvc.perform(post("/api/internships/manual")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // 4. Supervisor lists candidates: sees cand1, NOT cand2
        mockMvc.perform(get("/api/candidates")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + cand1.getId() + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + cand2.getId() + "')]").doesNotExist());

        // 5. Supervisor accesses cand1 detail -> 200 OK
        mockMvc.perform(get("/api/candidates/" + cand1.getId())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cand1.getId().toString()));

        // 6. Supervisor accesses cand2 detail -> 404 NOT FOUND (no IDOR / existence leak)
        mockMvc.perform(get("/api/candidates/" + cand2.getId())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isNotFound());

        // 7. Admin sees cand2 detail -> 200 OK
        mockMvc.perform(get("/api/candidates/" + cand2.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cand2.getId().toString()));
    }

    @Test
    @DisplayName("Supervisor endpoints enforce ADMIN authorization without routing conflicts")
    void supervisorEndpointsSecurity() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User candidateUser = userRepository.saveAndFlush(new User("cand_role_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        Role candidateRole = roleRepository.findByCode("CANDIDATE").orElse(null);
        if (candidateRole != null) {
            candidateUser.getAssignedRoles().add(candidateRole);
            candidateUser = userRepository.saveAndFlush(candidateUser);
        }
        String candidateToken = jwtService.generateAccessToken(candidateUser.getId(), candidateUser.getEmail(), List.of("ROLE_CANDIDATE"));

        // Unauthenticated access to /api/supervisors returns 401
        mockMvc.perform(get("/api/supervisors"))
                .andExpect(status().isUnauthorized());

        // Candidate access to /api/supervisors returns 403
        mockMvc.perform(get("/api/supervisors")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isForbidden());

        // Admin access to /api/supervisors returns 200 OK
        mockMvc.perform(get("/api/supervisors")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Admin access to /api/supervisors/manage returns 200 OK
        mockMvc.perform(get("/api/supervisors/manage")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Credential responses assert Cache-Control: no-store")
    void credentialsResponsesHaveCacheControlNoStore() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        
        // 1. Supervisor Creation
        String supervisorEmail = "sup_cache_" + suffix + "@steg.tn";
        mockMvc.perform(post("/api/supervisors")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSupervisorRequest(supervisorEmail))))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
                
        // 2. Intern Account Reset Password
        User internUser = userRepository.saveAndFlush(new User("intern_cache_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        Role internRole = roleRepository.findByCode("INTERN").orElse(null);
        if (internRole != null) {
            internUser.getAssignedRoles().add(internRole);
            userRepository.saveAndFlush(internUser);
        }
        mockMvc.perform(post("/api/intern-accounts/" + internUser.getId() + "/reset-password")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
                
        // 3. Application Approval (Mobile Provisioning)
        Candidate cand = createCandidate("Cand_cache_" + suffix);
        tn.steg.backend.application.domain.model.InternshipApplication app = new tn.steg.backend.application.domain.model.InternshipApplication(
                "APP-CACHE-" + suffix, cand, tn.steg.backend.application.domain.model.ApplicationStatus.UNDER_REVIEW);
        app.setDesiredStartDate(LocalDate.now().plusWeeks(1));
        app.setDesiredEndDate(LocalDate.now().plusMonths(2));
        app = context.getBean(tn.steg.backend.application.infrastructure.persistence.InternshipApplicationRepository.class).saveAndFlush(app);
        try { context.getBean(tn.steg.backend.workflow.application.WorkflowService.class).spawnApplicationWorkflow(app); } catch(Exception e) {}
        
        tn.steg.backend.workflow.application.dto.ApplicationApprovalRequest req = new tn.steg.backend.workflow.application.dto.ApplicationApprovalRequest(
                adminUser.getId(), departmentRepository.findAll().get(0).getId());
        
        mockMvc.perform(post("/api/applications/" + app.getId() + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
    }

    private Candidate createCandidate(String prefix) throws Exception {
        User user = userRepository.saveAndFlush(new User(prefix.toLowerCase() + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(prefix.getBytes(StandardCharsets.UTF_8)));
        Candidate c = new Candidate(prefix, "Test", user.getEmail(), cinHash, university);
        c.setUser(user);
        return candidateRepository.saveAndFlush(c);
    }
}
