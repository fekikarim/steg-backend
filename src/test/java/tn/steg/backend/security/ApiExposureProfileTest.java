package tn.steg.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 2 (production hardening) — the springdoc and actuator surfaces are
 * profile-dependent:
 *
 * <ul>
 *   <li><b>dev/test</b> (this class, {@code NestedDevProfiles}): the OpenAPI
 *       contract and the full health/info/metrics/prometheus actuator set are
 *       exposed — the classification tables in {@code docs/security-review.md}
 *       document exactly this surface, and the three clients are generated
 *       from it locally.</li>
 *   <li><b>prod</b> ({@code NestedProdProfile}): springdoc is disabled
 *       entirely and the actuator keeps ONLY {@code health} — a supervisor
 *       probe must not be able to enumerate the API or read operational
 *       internals on a production host.</li>
 * </ul>
 *
 * <p>Both nested contexts run against real Postgres (Testcontainers); the
 * prod context boots with the SAME {@code application-prod.yml} the operator
 * deploys, over the test profile (JWT secret, statistics) with the CORS
 * allow-list supplied the way production supplies it (environment).
 */
@DisplayName("API exposure per profile — springdoc + actuator (Task 2)")
class ApiExposureProfileTest {

    private abstract static class Base {
        @Autowired private WebApplicationContext context;
        @Autowired private JwtService jwtService;
        @Autowired private RoleRepository roleRepository;
        @Autowired protected UserRepository userRepository;

        MockMvc mockMvc;

        @BeforeEach
        void setUpMockMvc() {
            mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        }

        String adminToken() {
            Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
            User admin = userRepository.saveAndFlush(new User(
                    "exposure_admin_" + UUID.randomUUID().toString().substring(0, 8) + "@test.tn",
                    "hash", UserStatus.ACTIVE));
            admin.getAssignedRoles().add(adminRole);
            admin = userRepository.saveAndFlush(admin);
            return jwtService.generateAccessToken(admin.getId(), admin.getEmail(), List.of("ROLE_ADMIN"));
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @Import(TestcontainersConfiguration.class)
    @DisplayName("dev/test profile: OpenAPI contract + full actuator set are exposed")
    class NestedDevProfiles extends Base {

        @Test
        @DisplayName("/v3/api-docs answers 200 anonymously and carries the openapi document")
        void apiDocsAreExposedInDev() throws Exception {
            String body = mockMvc.perform(get("/v3/api-docs"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThat(body).contains("\"openapi\"").contains("/api/");
        }

        @Test
        @DisplayName("/swagger-ui.html redirects to the UI (springdoc enabled)")
        void swaggerUiIsExposedInDev() throws Exception {
            int status = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
            assertThat(status).isIn(200, 302);
        }

        @Test
        @DisplayName("actuator health/info/metrics/prometheus are exposed in dev (metrics ADMIN-only)")
        void actuatorSetIsExposedInDev() throws Exception {
            assertThat(mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThat(mockMvc.perform(get("/actuator/info")).andReturn().getResponse().getStatus()).isEqualTo(200);
            String token = adminToken();
            assertThat(mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThat(mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @SpringBootTest(properties = {
            // Production resolves both from the environment; the test profile
            // cannot (prod yml overrides the test yml's resolved secret).
            "STEG_JWT_SECRET=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970",
            "steg.cors.allowed-origins=https://backoffice.steg.tn"
    })
    @ActiveProfiles({"test", "prod"})
    @Import(TestcontainersConfiguration.class)
    @DisplayName("prod profile: springdoc disabled, actuator limited to health")
    class NestedProdProfile extends Base {

        @Test
        @DisplayName("/v3/api-docs and the swagger UI are NOT exposed in production")
        void springdocIsDisabledInProd() throws Exception {
            assertThat(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus()).isEqualTo(404);
            assertThat(mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus()).isEqualTo(404);
            assertThat(mockMvc.perform(get("/swagger-ui/index.html")).andReturn().getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("the V27 QA seed accounts are disabled at prod boot — their public passwords cannot log in")
        void v27QaAccountsAreDisabledInProduction() throws Exception {
            // ProdQaSeedAccountCallback runs on Flyway AFTER_MIGRATE — before
            // the servlet container accepts any request: the three V27
            // accounts (public passwords in the repository) are INACTIVE.
            for (String email : List.of("admin.steg@steg.tn", "supervisor.steg@steg.tn", "finance.steg@steg.tn")) {
                var user = userRepository.findByEmail(email).orElseThrow();
                assertThat(user.getStatus()).as("%s status", email).isEqualTo(UserStatus.INACTIVE);
                assertThat(user.getEnabled()).as("%s enabled", email).isFalse();
            }
            // Behavioral: the documented public password no longer authenticates.
            mockMvc.perform(post("/api/auth/back-office-login")
                            .contentType(APPLICATION_JSON)
                            .content("{\"email\":\"admin.steg@steg.tn\",\"password\":\"Admin#2026\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is4xxClientError());
        }

        @Test
        @DisplayName("actuator health answers anonymously without component details; everything else is unexposed")
        void actuatorIsHealthOnlyInProd() throws Exception {
            var health = mockMvc.perform(get("/actuator/health")).andReturn();
            assertThat(health.getResponse().getStatus()).isEqualTo(200);
            assertThat(health.getResponse().getContentAsString())
                    .contains("\"status\"")
                    .doesNotContain("\"components\"");
            // info is permitAll in SecurityConfig but UNEXPOSED in prod → 404.
            assertThat(mockMvc.perform(get("/actuator/info")).andReturn().getResponse().getStatus()).isEqualTo(404);
            // metrics/prometheus: unexposed even for an authenticated ADMIN
            // (exposure wins over authorization) — anonymous also never 2xx.
            String token = adminToken();
            assertThat(mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(404);
            assertThat(mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(404);
            assertThat(mockMvc.perform(get("/actuator/metrics")).andReturn().getResponse().getStatus())
                    .isNotIn(200, 201, 202, 204, 301, 302);
        }
    }
}
