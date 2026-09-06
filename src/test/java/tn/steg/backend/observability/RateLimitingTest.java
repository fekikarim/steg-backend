package tn.steg.backend.observability;

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
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.candidate.domain.repository.UniversityRepository;
import tn.steg.backend.common.infrastructure.config.JpaAuditingConfig;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase A13 — Rate limiting / abuse protection. The authenticated-scoped
 * sliding window must reject an abusive burst with HTTP 429 + Retry-After
 * while keeping legitimate authorized requests (including other users and
 * other endpoints) fully usable.
 *
 * <p>The candidate assistant endpoint is exercised because AI is disabled in
 * the test profile, so every allowed request degrades instantly instead of
 * performing an LLM call; rejects happen in the aspect before the controller.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
@Transactional
@DisplayName("A13 — Rate limiting")
class RateLimitingTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String assistantQuery;
    private String assistantRetryAfter;

    private User createUser(String email) {
        return userRepository.save(new User(email, "hashed_password", UserStatus.ACTIVE));
    }

    private Candidate createCandidate(User user) throws Exception {
        University uni = universityRepository.findAll().stream().findFirst()
                .orElseGet(() -> universityRepository.save(new University("UNI_RL_TEST", "Rate Limit Test Uni")));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String hash = Base64.getEncoder().encodeToString(digest.digest(user.getEmail().getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("RateLimit", "User", user.getEmail(), hash, uni);
        candidate.setUser(user);
        return candidateRepository.save(candidate);
    }

    private String candidateToken(User user) {
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of("ROLE_CANDIDATE"));
    }

    private String adminToken(User user) {
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("burst beyond limit is rejected with 429 + Retry-After; allowed calls still succeed")
    void burstBeyondLimitReturns429() throws Exception {
        User candidate = createCandidate(createUser("rl_burst@test.tn")).getUser();
        String token = candidateToken(candidate);

        for (int i = 1; i <= 20; i++) {
            mockMvc.perform(post("/api/ai/assistant/query")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"archive des bulletins de paie\"}"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/ai/assistant/query")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"archive des bulletins de paie\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Rate Limit Exceeded"))
                .andExpect(result -> {
                    String retryAfter = result.getResponse().getHeader("Retry-After");
                    assistantRetryAfter = retryAfter;
                    assertThat(retryAfter).isEqualTo("60");
                });
        assertThat(assistantRetryAfter).isEqualTo("60");
    }

    @Test
    @DisplayName("a different authorized user is not affected by another user's exhausted bucket")
    void differentUserUnaffected() throws Exception {
        User aggressor = createCandidate(createUser("rl_aggro@test.tn")).getUser();
        String aggressorToken = candidateToken(aggressor);
        for (int i = 1; i <= 25; i++) {
            mockMvc.perform(post("/api/ai/assistant/query")
                            .header("Authorization", "Bearer " + aggressorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"solde de conges\"}"))
                    .andExpect(i <= 20 ? status().isOk() : status().isTooManyRequests());
        }

        User other = createCandidate(createUser("rl_other@test.tn")).getUser();
        mockMvc.perform(post("/api/ai/assistant/query")
                        .header("Authorization", "Bearer " + otherToken(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"solde de conges\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("rate limit buckets are per-endpoint: another endpoint stays usable after assistant burst")
    void endpointBucketsIndependent() throws Exception {
        User candidate = createCandidate(createUser("rl_multi@test.tn")).getUser();
        String candidateToken = candidateToken(candidate);
        for (int i = 1; i <= 25; i++) {
            mockMvc.perform(post("/api/ai/assistant/query")
                            .header("Authorization", "Bearer " + candidateToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"candidature\"}"))
                    .andExpect(i <= 20 ? status().isOk() : status().isTooManyRequests());
        }

        // Same user, different rate-limited endpoint: the AI analyze bucket is
        // independent, so the request is admitted by the limiter and reaches the
        // controller (which 404s on the fabricated application id). Crucially it
        // must NOT be a 429.
        User admin = createUser("rl_admin@test.tn");
        String adminToken = adminToken(admin);
        mockMvc.perform(post("/api/ai/applications/" + UUID.randomUUID() + "/analyze")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(result ->
                        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                                .isEqualTo(404));
    }

    private String otherToken(User user) {
        return candidateToken(user);
    }
}