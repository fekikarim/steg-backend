package tn.steg.backend.candidate.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.infrastructure.persistence.InternshipAssignmentRepository;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Persistence-backed proof for candidate scoping (AGENTS.md §1 / §3.4 / §6.2):
 * a Supervisor only lists and reads his own assigned candidates, a foreign
 * candidate returns 404 (never 403 — no existence leak), and the Admin sees all.
 *
 * <p>Uses a real Postgres through Testcontainers, and covers BOTH supervision
 * shapes: the direct internship link and the user-backed assignment row.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Candidate scoping persistence (Testcontainers)")
class CandidateScopingIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipAssignmentRepository assignmentRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private MockMvc mockMvc;

    private String run;
    private User admin;
    private User supervisorA;
    private User supervisorB;
    private Department department;
    private University university;

    private Candidate candidateA;
    private Candidate candidateB;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = UUID.randomUUID().toString().substring(0, 8);

        admin = newUser("admin", "ADMIN");
        supervisorA = newUser("supa", "SUPERVISOR");
        supervisorB = newUser("supb", "SUPERVISOR");
        department = departmentRepository.saveAndFlush(
                new Department("DEPT_CAND_SCOPE_" + run, "Dept Cand Scope " + run, "IT"));
        university = universityRepository.saveAndFlush(
                new University("UNI_CAND_SCOPE_" + run, "Uni Cand Scope " + run));

        // Candidate A is supervised through the direct internship link.
        candidateA = newCandidate("CAND_A_" + run);
        Internship internshipA = newInternship("INT-CAND-A-" + run, candidateA);
        internshipA.setSupervisorUser(supervisorA);
        internshipRepository.saveAndFlush(internshipA);

        // Candidate B is supervised ONLY through a user-backed assignment
        // (internship.supervisorUser left NULL).
        candidateB = newCandidate("CAND_B_" + run);
        Internship internshipB = newInternship("INT-CAND-B-" + run, candidateB);
        activeAssignment(internshipB, supervisorB);
    }

    @Test
    @DisplayName("a Supervisor lists only his own candidates; the Admin lists everyone")
    void supervisorListShowsOnlyOwnCandidates() throws Exception {
        assertThat(candidateIds(token(supervisorA, "ROLE_SUPERVISOR")))
                .containsExactly(candidateA.getId());
        assertThat(candidateIds(token(supervisorB, "ROLE_SUPERVISOR")))
                .containsExactly(candidateB.getId());

        assertThat(candidateIds(token(admin, "ROLE_ADMIN")))
                .contains(candidateA.getId(), candidateB.getId());
    }

    @Test
    @DisplayName("reading another supervisor's candidate returns 404, not 403 (no existence leak)")
    void readingForeignCandidateIsNotFound() throws Exception {
        mockMvc.perform(get("/api/candidates/" + candidateB.getId())
                        .header("Authorization", "Bearer " + token(supervisorA, "ROLE_SUPERVISOR")))
                .andExpect(status().isNotFound());

        // Own candidate is readable (and the CIN stays hidden from supervisors).
        MvcResult own = mockMvc.perform(get("/api/candidates/" + candidateA.getId())
                        .header("Authorization", "Bearer " + token(supervisorA, "ROLE_SUPERVISOR")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode ownBody = objectMapper.readTree(own.getResponse().getContentAsString());
        assertThat(ownBody.get("nationalId").isNull()).isTrue();

        // Admin can read any candidate.
        mockMvc.perform(get("/api/candidates/" + candidateB.getId())
                        .header("Authorization", "Bearer " + token(admin, "ROLE_ADMIN")))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<UUID> candidateIds(String bearer) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/candidates")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<UUID> ids = new ArrayList<>();
        array.forEach(node -> ids.add(UUID.fromString(node.get("id").asText())));
        return ids;
    }

    private User newUser(String prefix, String roleCode) {
        Role role = roleRepository.findByCode(roleCode).orElse(null);
        assertThat(role).as("seeded role " + roleCode).isNotNull();
        User user = new User(prefix + "_" + run + "@steg.tn", "hash", UserStatus.ACTIVE);
        user.getAssignedRoles().add(role);
        return userRepository.saveAndFlush(user);
    }

    private String token(User user, String role) {
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of(role));
    }

    private Candidate newCandidate(String label) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String cinHash = Base64.getEncoder().encodeToString(
                    digest.digest((label + run).getBytes(StandardCharsets.UTF_8)));
            Candidate candidate = new Candidate("Candidat", label,
                    "cand_" + label.toLowerCase() + "_" + run + "@steg.tn", cinHash, university);
            return candidateRepository.saveAndFlush(candidate);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Internship newInternship(String reference, Candidate candidate) {
        Internship internship = new Internship(reference, candidate,
                LocalDate.now(), LocalDate.now().plusMonths(2),
                InternshipType.PERFECTIONNEMENT, InternshipRequirement.OBLIGATOIRE);
        internship.setStatus(InternshipStatus.APPROVED);
        return internshipRepository.saveAndFlush(internship);
    }

    private void activeAssignment(Internship internship, User supervisorUser) {
        InternshipAssignment assignment = new InternshipAssignment();
        assignment.setInternship(internship);
        assignment.setDestination(department);
        assignment.setSupervisorUser(supervisorUser);
        assignment.setAssignedByUser(supervisorUser);
        assignment.setAssignedAt(LocalDate.now());
        assignment.setStartDate(internship.getStartDate());
        assignment.setEndDate(internship.getEndDate());
        assignment.setStatus(AssignmentStatus.ACTIVE);
        assignmentPort().saveAndFlush(assignment);
    }

    private tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository assignmentPort() {
        return assignmentRepository;
    }
}
