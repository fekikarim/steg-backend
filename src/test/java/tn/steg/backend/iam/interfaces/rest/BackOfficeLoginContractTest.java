package tn.steg.backend.iam.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pre-fix (c), audit §1 "exactly Admin and Supervisor in back office":
 * the back-office login contract is enforced server-side —
 * {@code POST /api/auth/back-office-login} issues tokens to ADMIN and
 * SUPERVISOR accounts only; any other role is refused with 403 and no
 * token is ever issued (Testcontainers).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Back-office login contract (Testcontainers)")
class BackOfficeLoginContractTest {

    private static final String PASSWORD = "St4ff!Test-Pass9";

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private String run;

    private String adminEmail;
    private String supervisorEmail;
    private String candidateEmail;
    private String internEmail;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = UUID.randomUUID().toString().substring(0, 8);

        adminEmail = createUser("bol_admin_" + run, "ADMIN");
        supervisorEmail = createUser("bol_sup_" + run, "SUPERVISOR");
        candidateEmail = createUser("bol_cand_" + run, "CANDIDATE");
        internEmail = createUser("bol_intern_" + run, "INTERN");
    }

    private String createUser(String localPart, String roleCode) {
        String email = (localPart + "@steg.tn").toLowerCase();
        User user = new User(email, passwordEncoder.encode(PASSWORD), UserStatus.ACTIVE);
        user.setEnabled(true);
        user = userRepository.saveAndFlush(user);
        Role role = roleRepository.findByCode(roleCode).orElseThrow();
        user.getAssignedRoles().add(role);
        userRepository.saveAndFlush(user);
        return email;
    }

    private String loginBody(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    @ParameterizedTest(name = "{0} receives tokens from the back-office login")
    @ValueSource(strings = {"ADMIN", "SUPERVISOR"})
    @DisplayName("ADMIN and SUPERVISOR log in to the back office")
    void staffRolesLogin(String roleCode) throws Exception {
        String email = "ADMIN".equals(roleCode) ? adminEmail : supervisorEmail;
        mockMvc.perform(post("/api/auth/back-office-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @ParameterizedTest(name = "{0} is refused by the back-office login")
    @ValueSource(strings = {"CANDIDATE", "INTERN"})
    @DisplayName("any other role is refused with 403 and receives no token")
    void nonStaffRolesRefused(String roleCode) throws Exception {
        String email = "CANDIDATE".equals(roleCode) ? candidateEmail : internEmail;
        String body = mockMvc.perform(post("/api/auth/back-office-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("BACK_OFFICE_DENIED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("accessToken");

        // The same credentials remain valid on the shared login: refusal is
        // about the back-office contract, not the credentials.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    @DisplayName("wrong credentials are still 422 AUTHENTICATION_FAILED, not 403")
    void wrongCredentialsStayUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/back-office-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(adminEmail, "Wr0ng!Pass")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("AUTHENTICATION_FAILED"));
    }
}
