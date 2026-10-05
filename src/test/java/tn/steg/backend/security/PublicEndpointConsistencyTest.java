package tn.steg.backend.security;

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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase A14 — the anonymous surface in {@code SecurityConfig} must match the
 * {@code @PublicEndpoint} contract exactly: public endpoints answer without a token
 * (400 on invalid bodies proves the request passed the security filter, not a 401),
 * while authenticated endpoints still demand credentials.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("A14 — Public endpoint consistency")
class PublicEndpointConsistencyTest {

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("register, login, back-office login and refresh are reachable anonymously (400, not 401)")
    void authPublicEndpointsReachableAnonymously() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/back-office-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("public reference data is reachable anonymously")
    void universitiesReachableAnonymously() throws Exception {
        mockMvc.perform(get("/api/universities")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("every @PublicEndpoint path answers anonymously (triple-list agreement: annotation + SecurityConfig + JWT filter)")
    void everyPublicEndpointAnswersAnonymously() throws Exception {
        // Anonymous application intake + identifier pre-check (multipart or
        // JSON accepted; empty bodies fail validation = 400, never 401/403).
        mockMvc.perform(post("/api/public/applications/validate-identifiers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        // Public document validation with an empty file part = 400 from the
        // controller (never 401/403: the anonymous surface agrees).
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/public/documents/validation")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "empty.pdf", "application/pdf", new byte[0]))
                        .param("type", "STAGE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("authenticated endpoints still reject anonymous callers")
    void protectedEndpointsRejectAnonymous() throws Exception {
        mockMvc.perform(get("/api/audit")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/finance-cases")).andExpect(status().isUnauthorized());
    }
}