package tn.steg.backend.candidate.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.EducationLevel;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Set;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("Candidate API Integration Tests")
class CandidateControllerIntegrationTest {

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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("Public: /api/universities is accessible without authentication")
    void universitiesEndpointIsPublic() throws Exception {
        University uni = new University("ESPRIT_TEST", "Ecole Superieure Privee");
        universityRepository.saveAndFlush(uni);

        mockMvc.perform(get("/api/universities"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    @WithMockUser(username = "admin@steg.com", roles = {"ADMIN"})
    @DisplayName("Security: List candidates never contains nationalId")
    void listCandidatesExcludesNationalId() throws Exception {
        University uni = new University("INSAT_TEST", "Institut National des Sciences Appliquees");
        uni = universityRepository.saveAndFlush(uni);

        User user = new User("cand_test@steg.com", "hashed_pass", UserStatus.ACTIVE);
        user = userRepository.saveAndFlush(user);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String nationalId = "09876543";
        String hash = Base64.getEncoder().encodeToString(digest.digest(nationalId.getBytes(StandardCharsets.UTF_8)));

        Candidate candidate = new Candidate("Foulen", "Ben Foulen", "cand_test@steg.com", hash, uni);
        candidate.setUser(user);
        candidate.setNationalIdEncrypted(nationalId);
        candidateRepository.saveAndFlush(candidate);

        mockMvc.perform(get("/api/candidates"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("09876543"))));
    }
}
