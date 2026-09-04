package tn.steg.backend.application.interfaces.rest;

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
import tn.steg.backend.application.application.dto.ApplicationCreateRequest;
import tn.steg.backend.application.application.dto.ApplicationUpdateRequest;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.infrastructure.persistence.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("Application Integration and IDOR Guard Tests")
class ApplicationControllerIntegrationTest {

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
    private InternshipApplicationRepository applicationRepository;

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private Candidate createTestCandidate(String email, String cin, University uni) throws Exception {
        User user = new User(email, "hashed_password", UserStatus.ACTIVE);
        user = userRepository.saveAndFlush(user);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String hash = Base64.getEncoder().encodeToString(digest.digest(cin.getBytes(StandardCharsets.UTF_8)));

        Candidate candidate = new Candidate("First", "Last", email, hash, uni);
        candidate.setUser(user);
        candidate.setNationalIdEncrypted(cin);
        return candidateRepository.saveAndFlush(candidate);
    }

    @Test
    @DisplayName("Lifecycle: Candidate creates draft and receives server-generated reference APP-YYYY-NNNNN")
    void createDraftGeneratesReference() throws Exception {
        University uni = universityRepository.saveAndFlush(new University("UNI_APP_1", "Test Uni"));
        Candidate candidate = createTestCandidate("cand1@test.tn", "11223344", uni);

        String token = jwtService.generateAccessToken(
                candidate.getUser().getId(),
                candidate.getEmail(),
                List.of("ROLE_CANDIDATE")
        );

        ApplicationCreateRequest request = new ApplicationCreateRequest(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31),
                "Summer Engineering Internship in Smart Grids",
                true
        );

        mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.matchesPattern("^APP-\\d{4}-\\d{5}$")))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.proposedTheme").value("Summer Engineering Internship in Smart Grids"));
    }

    @Test
    @DisplayName("IDOR Protection: Candidate B cannot read or update Candidate A's application")
    void idorProtectionPreventsCrossCandidateAccess() throws Exception {
        University uni = universityRepository.saveAndFlush(new University("UNI_APP_2", "Test Uni 2"));
        Candidate candidateA = createTestCandidate("candA@test.tn", "12345678", uni);
        Candidate candidateB = createTestCandidate("candB@test.tn", "87654321", uni);

        String tokenA = jwtService.generateAccessToken(
                candidateA.getUser().getId(),
                candidateA.getEmail(),
                List.of("ROLE_CANDIDATE")
        );

        String tokenB = jwtService.generateAccessToken(
                candidateB.getUser().getId(),
                candidateB.getEmail(),
                List.of("ROLE_CANDIDATE")
        );

        // Candidate A creates draft
        ApplicationCreateRequest requestA = new ApplicationCreateRequest(
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 12, 31),
                "Candidate A project",
                true
        );

        String responseBody = mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestA)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode jsonNode = objectMapper.readTree(responseBody);
        String applicationId = jsonNode.get("id").asText();

        // Candidate B attempts to GET Candidate A's application -> 404 (or 403)
        mockMvc.perform(get("/api/applications/" + applicationId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // Candidate B attempts to PUT Candidate A's application -> 404 (or 403)
        ApplicationUpdateRequest updateRequest = new ApplicationUpdateRequest(
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 11, 30),
                "Hacked Theme",
                true
        );

        mockMvc.perform(put("/api/applications/" + applicationId)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isNotFound());
    }
}
