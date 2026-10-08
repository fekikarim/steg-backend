package tn.steg.backend.iam.interfaces.rest;

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
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T14 — {@code PUT /api/users/me/locale} persists the caller's own
 * preference (Testcontainers, real Postgres).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("T14 — Locale preference persistence")
class LocalePreferenceTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private User user;
    private String token;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        user = userRepository.saveAndFlush(new User("t14_loc_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        token = jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of("ROLE_INTERN"));
    }

    @Test
    @DisplayName("L1: a supported locale persists and reads back per user")
    void supportedLocalePersists() throws Exception {
        mockMvc.perform(put("/api/users/me/locale")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"ar\"}"))
                .andExpect(status().isNoContent());

        assertThat(((tn.steg.backend.iam.domain.repository.UserRepository) userRepository).findById(user.getId()).orElseThrow()
                .getPreferredLocale()).isEqualTo("ar");
    }

    @Test
    @DisplayName("L2: an unsupported locale falls back to French, never 400s")
    void unsupportedLocaleFallsBack() throws Exception {
        mockMvc.perform(put("/api/users/me/locale")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"de\"}"))
                .andExpect(status().isNoContent());

        assertThat(((tn.steg.backend.iam.domain.repository.UserRepository) userRepository).findById(user.getId()).orElseThrow()
                .getPreferredLocale()).isEqualTo("fr");
    }

    @Test
    @DisplayName("L3: anonymous callers are refused")
    void anonymousRefused() throws Exception {
        mockMvc.perform(put("/api/users/me/locale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"ar\"}"))
                .andExpect(status().isUnauthorized());
    }
}
