package tn.steg.backend.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.common.infrastructure.config.JpaAuditingConfig;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase A13 — the audit viewer must be strictly ADMIN-only and expose the
 * unified AuditLog read-only (no mutation endpoints exist by design).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
@Transactional
@DisplayName("A13 — Audit viewer security (GET /api/audit)")
class AuditControllerSecurityTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AuditService auditService;

    private MockMvc mockMvc;

    private String adminToken;
    private String hrToken;
    private String candidateToken;

    private UUID adminId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        User admin = userRepository.saveAndFlush(new User("audit_admin@steg.tn", "hash", UserStatus.ACTIVE));
        adminId = admin.getId();
        adminToken = jwtService.generateAccessToken(admin.getId(), admin.getEmail(), List.of("ROLE_ADMIN"));

        User hr = userRepository.saveAndFlush(new User("audit_hr@steg.tn", "hash", UserStatus.ACTIVE));
        hrToken = jwtService.generateAccessToken(hr.getId(), hr.getEmail(), List.of("ROLE_HR"));

        User candidate = userRepository.saveAndFlush(new User("audit_cand@steg.tn", "hash", UserStatus.ACTIVE));
        candidateToken = jwtService.generateAccessToken(candidate.getId(), candidate.getEmail(), List.of("ROLE_CANDIDATE"));

        // Seed one deterministic audit entry through the real writer.
        auditService.log("TEST_APPLICATION_ACCEPTED", "InternshipApplication",
                UUID.randomUUID(), null, null, adminId, "127.0.0.1");
    }

    @Test
    @DisplayName("unauthenticated -> 401")
    void unauthenticatedForbidden() throws Exception {
        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("HR and CANDIDATE roles -> 403 (ADMIN only)")
    void nonAdminForbidden() throws Exception {
        mockMvc.perform(get("/api/audit").header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit").header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN sees the seeded entry, filtrable by action")
    void adminCanReadAndFilter() throws Exception {
        mockMvc.perform(get("/api/audit").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(not(0)))
                .andExpect(jsonPath("$.content[*].action").value(org.hamcrest.Matchers.hasItem("TEST_APPLICATION_ACCEPTED")));

        mockMvc.perform(get("/api/audit").header("Authorization", "Bearer " + adminToken)
                        .param("action", "TEST_APPLICATION_ACCEPTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].action").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("TEST_APPLICATION_ACCEPTED"))));
    }
}