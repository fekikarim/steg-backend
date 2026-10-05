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
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pre-check (a) + (c) for the S5 candidates workspace, against real Postgres.
 *
 * <ul>
 *   <li><b>(a) the CIN rule is SELF-SERVICE only.</b> {@code POST /api/candidates}
 *       is {@code @authz.hasRole('CANDIDATE')}: it is the front-office
 *       onboarding endpoint, so requiring a CIN there matches the front-office
 *       form, which already refuses to submit without one. S6a added a SEPARATE
 *       staff endpoint ({@code POST /api/candidates/manage}); this one still
 *       refuses an Admin/Supervisor token with 403, so a staff client can never
 *       create somebody else's profile here (see
 *       {@code StaffCandidateLifecycleIntegrationTest}).</li>
 *   <li><b>(a) update stays optional.</b> A blank/omitted CIN keeps the stored
 *       value (staff edit without ever receiving it); a provided CIN is
 *       re-hashed and uniqueness-checked; self always gets the CIN back.</li>
 *   <li><b>(c) soft delete releases both identity anchors.</b> A soft delete is
 *       a tombstone: the row survives for audit/history but is hidden
 *       everywhere. {@code V42} moves CIN uniqueness to LIVE rows only and the
 *       delete releases {@code user_id}, so the same person (same CIN) and the
 *       same contact email can register again — while two LIVE profiles can
 *       never share a CIN and never share an account.</li>
 *   <li><b>(c) a soft-deleted login reaches nothing.</b> The delete is refused
 *       while the candidate has applications/internships/tasks/receipts, so a
 *       deleted profile has no data left; {@code /candidates/me} answers 404
 *       for both read and write and the profile never reappears in a staff
 *       queue, detail or legacy list.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Candidate self-service CIN rule + soft-delete re-registration (Testcontainers)")
class CandidateSelfServiceLifecycleIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private MockMvc mockMvc;

    private String run;
    private University university;
    private User candidateUser;
    private User secondUser;
    private User admin;
    private String candidateToken;
    private String secondToken;
    private String adminToken;
    private String thirdToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = UUID.randomUUID().toString().substring(0, 8);

        candidateUser = newUser("cand");
        secondUser = newUser("cand2");
        admin = newUser("admin", "ADMIN");
        candidateToken = token(candidateUser, "ROLE_CANDIDATE");
        secondToken = token(secondUser, "ROLE_CANDIDATE");
        thirdToken = token(newUser("cand3"), "ROLE_CANDIDATE");
        adminToken = token(admin, "ROLE_ADMIN");

        departmentRepository.saveAndFlush(new Department("DEPT_CSL_" + run, "Dept CSL " + run, "IT"));
        university = universityRepository.saveAndFlush(
                new University("UNI_CSL_" + run, "SelfService University " + run));
    }

    // =========================================================================
    // (a) The CIN rule is a self-service rule
    // =========================================================================

    @Test
    @DisplayName("creating a profile without a CIN is refused with CANDIDATE_NATIONAL_ID_REQUIRED")
    void createWithoutCinIsRefused() throws Exception {
        // Omitted entirely.
        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType("application/json")
                        .content(createBody("csl_a_" + run + "@steg.tn", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CANDIDATE_NATIONAL_ID_REQUIRED"));

        // Explicit null (what the front office sends as `... || null`).
        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType("application/json")
                        .content(createBody("csl_b_" + run + "@steg.tn", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CANDIDATE_NATIONAL_ID_REQUIRED"));

        // Blank string must behave the same as null (no `""` profile sneaks in).
        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType("application/json")
                        .content(createBody("csl_c_" + run + "@steg.tn", "   ")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CANDIDATE_NATIONAL_ID_REQUIRED"));

        // Nothing was persisted by any of the three attempts.
        assertThat(candidateRepository.findByUserId(candidateUser.getId())).isEmpty();
    }

    @Test
    @DisplayName("creating with a CIN succeeds and the CIN is returned to its owner")
    void createWithCinSucceedsAndReturnsItToSelf() throws Exception {
        String cin = "CIN-" + run + "-SELF";
        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType("application/json")
                        .content(createBody("csl_ok_" + run + "@steg.tn", cin)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nationalId").value(cin))
                .andExpect(jsonPath("$.email").value("csl_ok_" + run + "@steg.tn"))
                .andExpect(jsonPath("$.universityId").value(university.getId().toString()));

        // The stored value is hashed for uniqueness, never stored twice in clear.
        Candidate stored = candidateRepository.findByUserId(candidateUser.getId()).orElseThrow();
        assertThat(stored.getNationalIdHash()).isNotEqualTo(cin).doesNotContain(cin);
        assertThat(stored.getNationalIdEncrypted()).isEqualTo(cin);
        assertThat(stored.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("a second profile for the same account is refused (CANDIDATE_PROFILE_EXISTS)")
    void secondProfileForSameAccountIsRefused() throws Exception {
        createProfile(candidateToken, "csl_dup_" + run + "@steg.tn", "CIN-" + run + "-DUP");

        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType("application/json")
                        .content(createBody("csl_other_" + run + "@steg.tn", "CIN-" + run + "-OTHER")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CANDIDATE_PROFILE_EXISTS"));
    }

    @Test
    @DisplayName("a duplicate CIN is refused even from a different account")
    void duplicateCinIsRefused() throws Exception {
        String cin = "CIN-" + run + "-SHARED";
        createProfile(candidateToken, "csl_first_" + run + "@steg.tn", cin);

        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + secondToken)
                        .contentType("application/json")
                        .content(createBody("csl_second_" + run + "@steg.tn", cin)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CANDIDATE_NATIONAL_ID_DUPLICATE"));
    }

    @Test
    @DisplayName("self-service creation stays CANDIDATE-only after staff creation was added (S6a)")
    void selfServiceEndpointStaysCandidateOnly() throws Exception {
        // UPDATED in S6a: staff DO create profiles now, but on a DIFFERENT
        // endpoint (POST /api/candidates/manage, staff-only). This one remains
        // the front-office self-registration of the caller's OWN profile, so an
        // Admin/Supervisor token can never create somebody else's profile here.
        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(createBody("csl_staff_" + run + "@steg.tn", "CIN-" + run + "-STAFF")))
                .andExpect(status().isForbidden());
        assertThat(candidateRepository.findAll())
                .noneMatch(c -> "csl_staff_".concat(run).equals(c.getEmail()));

        String supervisorToken = token(newUser("sup", "SUPERVISOR"), "ROLE_SUPERVISOR");
        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType("application/json")
                        .content(createBody("csl_staff2_" + run + "@steg.tn", "CIN-" + run + "-STAFF2")))
                .andExpect(status().isForbidden());
        assertThat(candidateRepository.findAll())
                .noneMatch(c -> "csl_staff2_".concat(run).equals(c.getEmail()));
    }

    @Test
    @DisplayName("self update keeps the stored CIN when it is omitted or blank")
    void selfUpdateWithoutCinKeepsStoredValue() throws Exception {
        String cin = "CIN-" + run + "-KEEP";
        createProfile(candidateToken, "csl_keep_" + run + "@steg.tn", cin);

        // Omitted.
        mockMvc.perform(put("/api/candidates/me")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType("application/json")
                        .content(updateBody("csl_keep_" + run + "@steg.tn", "Keep", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nationalId").value(cin))
                .andExpect(jsonPath("$.firstName").value("Keep"));

        // Blank: same stored value, never overwritten with "".
        mockMvc.perform(put("/api/candidates/me")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType("application/json")
                        .content(updateBody("csl_keep_" + run + "@steg.tn", "Keep2", "  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nationalId").value(cin))
                .andExpect(jsonPath("$.firstName").value("Keep2"));

        Candidate stored = candidateRepository.findByUserId(candidateUser.getId()).orElseThrow();
        assertThat(stored.getNationalIdEncrypted()).isEqualTo(cin);
    }

    @Test
    @DisplayName("self update can replace the CIN and is still uniqueness-checked")
    void selfUpdateCanReplaceTheCin() throws Exception {
        String first = "CIN-" + run + "-OLD";
        String second = "CIN-" + run + "-NEW";
        createProfile(candidateToken, "csl_chg_" + run + "@steg.tn", first);

        mockMvc.perform(put("/api/candidates/me")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType("application/json")
                        .content(updateBody("csl_chg_" + run + "@steg.tn", "Chg", second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nationalId").value(second));

        // Taking another account's CIN is refused and the stored value survives.
        createProfile(secondToken, "csl_owner_" + run + "@steg.tn", "CIN-" + run + "-OWNED");
        mockMvc.perform(put("/api/candidates/me")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType("application/json")
                        .content(updateBody("csl_chg_" + run + "@steg.tn", "Chg", "CIN-" + run + "-OWNED")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CANDIDATE_NATIONAL_ID_DUPLICATE"));

        assertThat(candidateRepository.findByUserId(candidateUser.getId()).orElseThrow()
                .getNationalIdEncrypted()).isEqualTo(second);
    }

    @Test
    @DisplayName("self profile read returns the CIN to its owner and never leaks it elsewhere")
    void selfProfileReadIsEntitledAndScoped() throws Exception {
        String cin = "CIN-" + run + "-READ";
        createProfile(candidateToken, "csl_read_" + run + "@steg.tn", cin);

        mockMvc.perform(get("/api/candidates/me")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nationalId").value(cin));

        // The staff queue never carries a CIN (row contract).
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].nationalId").doesNotExist());
    }

    // =========================================================================
    // (c) Soft delete vs. re-registration, and what the deleted login can reach
    // =========================================================================

    @Test
    @DisplayName("after a soft delete the same CIN can be registered again by a new account")
    void sameCinCanBeRegisteredAgainAfterSoftDelete() throws Exception {
        String cin = "CIN-" + run + "-GONE";
        createProfile(candidateToken, "csl_gone_" + run + "@steg.tn", cin);
        softDelete(adminToken, "csl_gone_" + run + "@steg.tn");

        // A CIN is a permanent national identity: the same person must be able
        // to register again once their profile was deleted (V42 / assumption #13).
        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + secondToken)
                        .contentType("application/json")
                        .content(createBody("csl_again_" + run + "@steg.tn", cin)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nationalId").value(cin));

        // ...but that newly LIVE profile now holds the CIN: a third account
        // asking for it is refused (live uniqueness, V42).
        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + thirdToken)
                        .contentType("application/json")
                        .content(createBody("csl_fourth_" + run + "@steg.tn", cin)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CANDIDATE_NATIONAL_ID_DUPLICATE"));

        // The tombstone row survived (history/audit keep their foreign keys).
        Candidate softDeleted = candidateRepository.findAll().stream()
                .filter(c -> "csl_gone_".concat(run).concat("@steg.tn").equals(c.getEmail()))
                .findFirst()
                .orElseThrow();
        assertThat(softDeleted.getDeletedAt()).isNotNull();
        assertThat(softDeleted.getNationalIdEncrypted()).isEqualTo(cin);
    }

    @Test
    @DisplayName("the same EMAIL registers again freely (the contact email is never an identity anchor)")
    void sameEmailCanRegisterAgain() throws Exception {
        String email = "csl_re_" + run + "@steg.tn";
        createProfile(candidateToken, email, "CIN-" + run + "-FIRST");
        softDelete(adminToken, email);

        // A fresh account with the same contact email is accepted.
        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + secondToken)
                        .contentType("application/json")
                        .content(createBody(email, "CIN-" + run + "-SECOND")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nationalId").value("CIN-" + run + "-SECOND"));

        // Both rows exist: the deleted one hidden, the live one visible.
        assertThat(candidateRepository.findAll().stream()
                .filter(c -> email.equals(c.getEmail())))
                .hasSize(2);
    }

    @Test
    @DisplayName("a soft-deleted login reaches no data: /candidates/me is 404 for read and write")
    void softDeletedLoginReachesNoData() throws Exception {
        String email = "csl_lock_" + run + "@steg.tn";
        createProfile(candidateToken, email, "CIN-" + run + "-LOCK");
        softDelete(adminToken, email);

        mockMvc.perform(get("/api/candidates/me")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/candidates/me")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType("application/json")
                        .content(updateBody(email, "Ghost", null)))
                .andExpect(status().isNotFound());

        // The account is released by the soft delete, so the SAME account can
        // register again — with its own CIN, which is now free too.
        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + candidateToken)
                        .contentType("application/json")
                        .content(createBody(email, "CIN-" + run + "-AGAIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nationalId").value("CIN-" + run + "-AGAIN"));

        // The tombstone released the account link (one live profile per account).
        assertThat(candidateRepository.findAll().stream()
                .filter(c -> email.equals(c.getEmail()) && c.getDeletedAt() == null)
                .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a soft-deleted profile disappears from the staff queue, detail and legacy list")
    void softDeletedProfileIsInvisibleToStaff() throws Exception {
        String email = "csl_hide_" + run + "@steg.tn";
        createProfile(candidateToken, email, "CIN-" + run + "-HIDE");
        UUID id = candidateRepository.findByUserId(candidateUser.getId()).orElseThrow().getId();

        softDelete(adminToken, email);

        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        mockMvc.perform(get("/api/candidates/{id}", id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/candidates/{id}/overview", id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/candidates/{id}", id)
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isNotFound());

        // Legacy bare list (used by the application queue / internship pickers).
        MvcResult legacy = mockMvc.perform(get("/api/candidates")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode array = objectMapper.readTree(legacy.getResponse().getContentAsString());
        assertThat(array.toString()).doesNotContain(id.toString());
    }

    @Test
    @DisplayName("a new application never resurrects a soft-deleted profile")
    void applicationSubmissionDoesNotResurrectASoftDeletedProfile() throws Exception {
        String cin = "CIN-" + run + "-APPLY";
        createProfile(candidateToken, "csl_apply_" + run + "@steg.tn", cin);
        Candidate tombstoneBefore = candidateRepository.findAll().stream()
                .filter(c -> cin.equals(c.getNationalIdEncrypted()))
                .findFirst()
                .orElseThrow();
        UUID tombstoneId = tombstoneBefore.getId();
        softDelete(adminToken, "csl_apply_" + run + "@steg.tn");

        // The front-office identifier pre-check must NOT report the CIN as a
        // duplicate any more (live-only lookups).
        MvcResult validation = mockMvc.perform(post("/api/public/applications/validate-identifiers")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("nationalId", cin, "email", "csl_apply_new_" + run + "@steg.tn"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(validation.getResponse().getContentAsString());
        assertThat(json.get("duplicateFields").toString()).doesNotContain("nationalId");

        // An anonymous application with that CIN creates a FRESH live profile;
        // the tombstone must not be reused or re-activated.
        String payload = objectMapper.writeValueAsString(Map.of(
                "firstName", "Fresh",
                "lastName", "Applicant",
                "email", "csl_apply_new_" + run + "@steg.tn",
                "nationalId", cin,
                "universityId", university.getId().toString(),
                "desiredStartDate", "2026-06-01",
                "desiredEndDate", "2026-07-31"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/public/applications")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "application", "application", "application/json",
                                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .andExpect(status().isCreated());

        Candidate tombstoneAfter = candidatePort().findById(tombstoneId).orElseThrow();
        assertThat(tombstoneAfter.getDeletedAt()).as("tombstone stays deleted").isNotNull();
        assertThat(tombstoneAfter.getEmail()).as("tombstone is not rewritten")
                .isEqualTo("csl_apply_" + run + "@steg.tn");

        List<Candidate> liveWithCin = candidateRepository.findAll().stream()
                .filter(c -> cin.equals(c.getNationalIdEncrypted()) && c.getDeletedAt() == null)
                .toList();
        assertThat(liveWithCin).hasSize(1);
        assertThat(liveWithCin.get(0).getId()).isNotEqualTo(tombstoneId);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private User newUser(String prefix) {
        return newUser(prefix, "CANDIDATE");
    }

    private User newUser(String prefix, String roleCode) {
        Role role = roleRepository.findByCode(roleCode).orElse(null);
        assertThat(role).as("seeded role " + roleCode).isNotNull();
        User user = new User(prefix + "_csl_" + run + "@steg.tn", "hash", UserStatus.ACTIVE);
        user.getAssignedRoles().add(role);
        return userRepository.saveAndFlush(user);
    }

    private String token(User user, String role) {
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of(role));
    }

    /** The infra repo extends both JpaRepository and the domain port; use the port view. */
    private tn.steg.backend.candidate.domain.repository.CandidateRepository candidatePort() {
        return candidateRepository;
    }

    private String createBody(String email, String nationalId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Self");
        body.put("lastName", "Service");
        body.put("email", email);
        body.put("universityId", university.getId().toString());
        if (nationalId != null) {
            body.put("nationalId", nationalId);
        }
        return objectMapper.writeValueAsString(body);
    }

    private String updateBody(String email, String firstName, String nationalId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", "Service");
        body.put("email", email);
        body.put("universityId", university.getId().toString());
        if (nationalId != null) {
            body.put("nationalId", nationalId);
        }
        return objectMapper.writeValueAsString(body);
    }

    private void createProfile(String bearer, String email, String cin) throws Exception {
        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType("application/json")
                        .content(createBody(email, cin)))
                .andExpect(status().isCreated());
    }

    private void softDelete(String adminBearer, String email) throws Exception {
        Candidate stored = candidateRepository.findAll().stream()
                .filter(c -> email.equals(c.getEmail()))
                .findFirst()
                .orElseThrow();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/candidates/{id}", stored.getId())
                        .header("Authorization", "Bearer " + adminBearer))
                .andExpect(status().isNoContent());
    }
}
