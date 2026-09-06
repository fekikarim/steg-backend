package tn.steg.backend.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.common.infrastructure.config.JpaAuditingConfig;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase A13 — Actuator exposure & security hardening. Health/probes/info must
 * stay reachable by infra probes without secrets; the operational measurement
 * endpoints (metrics, prometheus) must be ADMIN-only; everything else
 * (env, configprops, threaddump, heapdump, loggers) must not be exposed at all.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
@Transactional
@DisplayName("A13 — Actuator exposure & security")
class ActuatorSecurityTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private final Map<String, User> roleUsers = new java.util.HashMap<>();
    private String adminToken;
    private String hrToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        adminToken = tokenForRole("ADMIN");
        hrToken = tokenForRole("HR");
    }

    private String tokenForRole(String role) {
        User user = roleUsers.computeIfAbsent(role, r ->
                userRepository.saveAndFlush(new User("actuator_" + r.toLowerCase() + "@steg.tn", "hash", UserStatus.ACTIVE)));
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of("ROLE_" + role));
    }

    @Test
    @DisplayName("health and probes are reachable anonymously but reveal no components")
    void healthAndProbesAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());

        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("build info is reachable anonymously")
    void infoAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("metrics + prometheus are ADMIN-only")
    void metricsAdminOnly() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("risky and sensitive actuator endpoints are not exposed at all")
    void sensitiveEndpointsUnexposed() throws Exception {
        // Unauthenticated: the shared auth gate answers 401 before the
        // endpoint layer can even be consulted. Authenticated (ADMIN): these
        // endpoints are absent from the web exposure, so they NEVER answer a
        // success — known-disabled ids surface the Actuator error (5xx),
        // unknown ids a 404; either way no data is returned. The discovery
        // /actuator map is excluded from that set (it is an ADMIN-gated
        // name->HTTP mapping and leaks nothing).
        for (String path : new String[]{"/actuator/env", "/actuator/configprops",
                "/actuator/beans", "/actuator/threaddump", "/actuator/heapdump",
                "/actuator/loggers", "/actuator/mappings", "/actuator/shutdown",
                "/actuator/does-not-exist"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("Authorization", "Bearer " + adminToken))
                    .andExpect(result -> org.assertj.core.api.Assertions
                            .assertThat(result.getResponse().getStatus())
                            .as("sensitive actuator path %s must never answer 2xx", path)
                            .isNotEqualTo(200));
        }
        mockMvc.perform(get("/actuator")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}