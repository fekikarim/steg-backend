package tn.steg.backend.internship.interfaces.rest;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.audit.domain.repository.AuditLogRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T14/D14 — supervisor asks his own students to prepare validation
 * documents (Testcontainers, real Postgres).
 *
 * <p>Direct dispatch (no BEFORE_COMMIT listener involved), so the tests
 * run {@code @Transactional} and read their own writes.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("T14 — Document preparation notification (own scope, rate limit, idempotency)")
class DocumentPreparationNotificationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private JwtService jwtService;
    @Autowired private AuditLogRepository auditLogRepository;

    private MockMvc mockMvc;
    private String supAToken;
    private String supBToken;
    private String internToken;
    private String internB1Token;
    private String supervisorEmail;
    private UUID internshipA1;
    private UUID internshipA2;
    private UUID internshipB1;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User supA = userRepository.saveAndFlush(new User("t14_supA_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        supAToken = jwtService.generateAccessToken(supA.getId(), supA.getEmail(), List.of("ROLE_SUPERVISOR"));
        supervisorEmail = supA.getEmail();

        User supB = userRepository.saveAndFlush(new User("t14_supB_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        supBToken = jwtService.generateAccessToken(supB.getId(), supB.getEmail(), List.of("ROLE_SUPERVISOR"));

        User internA1 = userRepository.saveAndFlush(new User("t14_iA1_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internA1.getId(), internA1.getEmail(), List.of("ROLE_INTERN"));

        University uni = universityRepository.saveAndFlush(new University("UNI_T14_" + suffix, "T14 Uni"));
        Department dept = departmentRepository.saveAndFlush(new Department("DEPT_T14_" + suffix, "T14 Dept", "T14"));

        Employee empA = employeeRepository.saveAndFlush(new Employee("EMP_T14A_" + suffix, "Sana", "A", dept));
        empA.setUser(supA);
        empA = employeeRepository.saveAndFlush(empA);
        Employee empB = employeeRepository.saveAndFlush(new Employee("EMP_T14B_" + suffix, "Karim", "B", dept));
        empB.setUser(supB);
        employeeRepository.saveAndFlush(empB);

        User hrUser = userRepository.saveAndFlush(new User("t14_hr_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        var hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_ADMIN"));

        internshipA1 = newInternship(internA1, uni, dept, empA, hrPrincipal, suffix + "A1");
        User internA2 = userRepository.saveAndFlush(new User("t14_iA2_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        internshipA2 = newInternship(internA2, uni, dept, empA, hrPrincipal, suffix + "A2");
        User internB1 = userRepository.saveAndFlush(new User("t14_iB1_" + suffix + "@test.tn", "hash", UserStatus.ACTIVE));
        internB1Token = jwtService.generateAccessToken(internB1.getId(), internB1.getEmail(), List.of("ROLE_INTERN"));
        internshipB1 = newInternship(internB1, uni, dept, empB, hrPrincipal, suffix + "B1");
    }

    private UUID newInternship(User intern, University uni, Department dept, Employee sup,
                               UserPrincipal hr, String cin) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(cin.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Notify", "Me", intern.getEmail(), cinHash, uni);
        candidate.setUser(intern);
        candidate.setNationalIdEncrypted(cin);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse internship = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.now().minusDays(30),
                LocalDate.now().plusDays(60),
                "T14 project",
                "Ingénieur",
                false), hr);
        internshipService.assign(internship.id(), new InternshipAssignmentRequest(
                dept.getId(), sup.getId(),
                LocalDate.now().minusDays(30), LocalDate.now().plusDays(60),
                "T14 assignment"), hr);
        return internship.id();
    }

    @Test
    @DisplayName("P1: two own students are notified, exactly once each")
    void ownStudentsNotifiedOnceEach() throws Exception {
        mockMvc.perform(post("/api/supervision/document-preparation")
                        .header("Authorization", "Bearer " + supAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("internshipIds", List.of(internshipA1, internshipA2)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notified").value(2));

        String payload = notifications(internToken);
        assertThat(payload).contains("DOCUMENTS_PREPARATION_REQUESTED");
        assertThat(payload).doesNotContain(supervisorEmail);
        assertThat(countOccurrences(payload, "DOCUMENTS_PREPARATION_REQUESTED")).isEqualTo(1);
        // No cross-student leak: a student who was NOT selected receives
        // nothing (he is not in this supervisor's scope either).
        assertThat(notifications(internB1Token))
                .doesNotContain("DOCUMENTS_PREPARATION_REQUESTED");
    }

    @Test
    @DisplayName("P2: an out-of-scope id aborts with 404 and nobody is notified")
    void outOfScopeAbortsAtomically() throws Exception {
        mockMvc.perform(post("/api/supervision/document-preparation")
                        .header("Authorization", "Bearer " + supAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("internshipIds", List.of(internshipA1, internshipB1)))))
                .andExpect(status().isNotFound());

        assertThat(notifications(internToken)).doesNotContain("DOCUMENTS_PREPARATION_REQUESTED");
    }

    @Test
    @DisplayName("P3: an empty selection is refused with 422")
    void emptySelectionRefused() throws Exception {
        mockMvc.perform(post("/api/supervision/document-preparation")
                        .header("Authorization", "Bearer " + supAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internshipIds\":[]}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    @DisplayName("P4: interns and anonymous callers are refused")
    void internAndAnonymousRefused() throws Exception {
        mockMvc.perform(post("/api/supervision/document-preparation")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("internshipIds", List.of(internshipA1)))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/supervision/document-preparation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("internshipIds", List.of(internshipA1)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("P5: replaying the idempotency key does not notify twice")
    void idempotentReplayNotifiesOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(
                Map.of("internshipIds", List.of(internshipA1)));
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/supervision/document-preparation")
                            .header("Authorization", "Bearer " + supAToken)
                            .header("X-Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.notified").value(1));
        }
        assertThat(countOccurrences(
                notifications(internToken), "DOCUMENTS_PREPARATION_REQUESTED"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("P6: the 11th call in a minute is rate-limited (429)")
    void rateLimitEnforced() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("internshipIds", List.of(internshipA1)));
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/supervision/document-preparation")
                            .header("Authorization", "Bearer " + supAToken)
                            .header("X-Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/supervision/document-preparation")
                        .header("Authorization", "Bearer " + supAToken)
                        .header("X-Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("P7: every covered internship is audited without personal data")
    void auditGenerated() throws Exception {
        mockMvc.perform(post("/api/supervision/document-preparation")
                        .header("Authorization", "Bearer " + supAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("internshipIds", List.of(internshipA1)))))
                .andExpect(status().isOk());

        var rows = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(
                "Internship", internshipA1);
        var row = rows.stream()
                .filter(r -> "DOCUMENT_PREPARATION_REQUESTED".equals(r.getAction()))
                .findFirst()
                .orElseThrow();
        // BR-55: the source must say MOBILE — this request comes from the
        // intern app, not the back office.
        assertThat(row.getSource().name()).isEqualTo("MOBILE");
        // BR-59: the audit payload carries the reference only — no intern
        // identity, no CIN, no email.
        assertThat(row.getNewValues())
                .doesNotContain("@")
                .doesNotContain("cin")
                .doesNotContain("Notify");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private String notifications(String bearer) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
