package tn.steg.backend.candidate.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S6a — staff creation of a candidate profile (AGENTS.md §5.1 Admin, §6.2
 * Supervisor in his own scope, ambiguity A4) against real Postgres.
 *
 * <p>What this pins:
 * <ul>
 *   <li>a staff-created profile has NO account ({@code user_id IS NULL}) and is
 *       linked to its manager through {@code managed_by_user_id} (V44);</li>
 *   <li>"own candidates" = active assignment OR {@code managed_by_user_id}, and
 *       the rule lives ONLY in SupervisionScopeService: supervisor A's queue and
 *       detail show the profile, supervisor B gets 404 (never 403) everywhere;</li>
 *   <li>the person later CLAIMS the profile by registering with the same CIN or
 *       the same email — no duplicate row, {@code managed_by} preserved;</li>
 *   <li>a profile owned by somebody else is never claimable (no identity merge);</li>
 *   <li>a soft-deleted staff profile is a tombstone: the person can register
 *       again with a FRESH row (V42/V44), and the tombstone leaves every queue;</li>
 *   <li>creating a profile never creates or approves an application — approval
 *       stays Admin-only (§5.2).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("S6a staff candidate creation, scope and claim (Testcontainers)")
class StaffCandidateLifecycleIntegrationTest {

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
    private User admin;
    private User supervisorA;
    private User supervisorB;
    private User ownerAccount;
    private User otherAccount;
    private String adminToken;
    private String supervisorAToken;
    private String supervisorBToken;
    private String ownerToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = UUID.randomUUID().toString().substring(0, 8);

        admin = newUser("admin", "ADMIN");
        supervisorA = newUser("supa", "SUPERVISOR");
        supervisorB = newUser("supb", "SUPERVISOR");
        ownerAccount = newUser("owner", "CANDIDATE");
        otherAccount = newUser("other", "CANDIDATE");

        adminToken = token(admin, "ROLE_ADMIN");
        supervisorAToken = token(supervisorA, "ROLE_SUPERVISOR");
        supervisorBToken = token(supervisorB, "ROLE_SUPERVISOR");
        ownerToken = token(ownerAccount, "ROLE_CANDIDATE");
        otherToken = token(otherAccount, "ROLE_CANDIDATE");

        departmentRepository.saveAndFlush(new Department("DEPT_S6A_" + run, "Dept S6A " + run, "IT"));
        university = universityRepository.saveAndFlush(
                new University("UNI_S6A_" + run, "S6A University " + run));
    }

    // =========================================================================
    // Creation
    // =========================================================================

    @Test
    @DisplayName("Admin creates a profile: no account, managed by the acting Admin, CIN returned")
    void adminCreatesCandidateManagedByHimself() throws Exception {
        String cin = "CIN-" + run + "-ADMIN";
        MvcResult created = staffCreate(adminToken, null,
                "s6a_admin_" + run + "@steg.tn", cin, "Created", "ByAdmin");

        JsonNode body = json(created);
        assertThat(body.get("nationalId").asText()).isEqualTo(cin);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);

        Candidate stored = liveProfileWithCin(cin);
        assertThat(stored.getUser()).as("staff creation never invents an account").isNull();
        assertThat(stored.getManagedBy()).as("default manager is the acting staff member")
                .isNotNull();
        assertThat(stored.getManagedBy().getId()).isEqualTo(admin.getId());
        assertThat(stored.getUniversity().getId()).isEqualTo(university.getId());
    }

    @Test
    @DisplayName("Admin may create a profile managed by another supervisor")
    void adminCanChooseAnotherManager() throws Exception {
        String cin = "CIN-" + run + "-PICK";
        staffCreate(adminToken, supervisorA.getId(), "s6a_pick_" + run + "@steg.tn", cin, "Pick", "Me");

        Candidate stored = liveProfileWithCin(cin);
        assertThat(stored.getManagedBy().getId()).isEqualTo(supervisorA.getId());

        // It lands in the chosen supervisor's scope, not the Admin's private one
        // (the Admin still sees everything globally, §3.2).
        assertThat(queueSize(supervisorAToken)).isEqualTo(1);
        assertThat(queueSize(supervisorBToken)).isZero();
        assertThat(queueSize(adminToken)).isEqualTo(1);
    }

    @Test
    @DisplayName("a Supervisor creates a profile in his own scope; another Supervisor gets 404")
    void supervisorCreatesInOwnScopeAndOthersAreOutOfScope() throws Exception {
        String cin = "CIN-" + run + "-SUPA";
        MvcResult created = staffCreate(supervisorAToken, null,
                "s6a_supa_" + run + "@steg.tn", cin, "Super", "VisorA");

        // A supervisor response stays redacted: the CIN is disclosed to the
        // candidate himself and to ADMIN only.
        assertThat(json(created).get("nationalId").isNull())
                .as("supervisor create response must not carry the CIN").isTrue();

        UUID id = liveProfileWithCin(cin).getId();
        assertThat(liveProfileWithCin(cin).getManagedBy().getId()).isEqualTo(supervisorA.getId());

        // In scope: queue + detail + aggregate + delete.
        assertThat(queueSize(supervisorAToken)).isEqualTo(1);
        mockMvc.perform(get("/api/candidates/{id}", id).header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/candidates/{id}/overview", id)
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk());

        // Out of scope: 404 everywhere, never 403 (§3.4) and never a leak.
        assertThat(queueSize(supervisorBToken)).isZero();
        mockMvc.perform(get("/api/candidates/{id}", id).header("Authorization", "Bearer " + supervisorBToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/candidates/{id}/overview", id)
                        .header("Authorization", "Bearer " + supervisorBToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/candidates/{id}", id)
                        .header("Authorization", "Bearer " + supervisorBToken))
                .andExpect(status().isNotFound());

        // The profile is untouched by the refused delete.
        assertThat(candidatePort().findById(id).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("a Supervisor cannot create a candidate managed by somebody else")
    void supervisorCannotCreateOutsideHisScope() throws Exception {
        String cin = "CIN-" + run + "-XSCOPE";
        // Omitting the manager means "myself" — allowed for a supervisor (§6.2).
        mockMvc.perform(post("/api/candidates/manage")
                        .header("Authorization", "Bearer " + supervisorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staffBody(null, "s6a_x_" + run + "@steg.tn", cin)))
                .andExpect(status().isCreated());

        // Naming ANOTHER supervisor is refused: he would lose sight of it.
        mockMvc.perform(post("/api/candidates/manage")
                        .header("Authorization", "Bearer " + supervisorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staffBody(supervisorB.getId(), "s6a_x2_" + run + "@steg.tn", cin)))
                .andExpect(status().isForbidden());

        // His OWN id spelled out is accepted (explicit but harmless).
        String ownCin = "CIN-" + run + "-XOWN";
        mockMvc.perform(post("/api/candidates/manage")
                        .header("Authorization", "Bearer " + supervisorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staffBody(supervisorA.getId(), "s6a_x3_" + run + "@steg.tn", ownCin)))
                .andExpect(status().isCreated());

        // Two profiles exist: the refused one was never persisted, and both
        // created profiles are managed by their creator.
        assertThat(liveProfiles().stream().filter(c -> cin.equals(c.getNationalIdEncrypted()))).hasSize(1);
        assertThat(liveProfiles().stream()
                .filter(c -> cin.equals(c.getNationalIdEncrypted()) || ownCin.equals(c.getNationalIdEncrypted())))
                .hasSize(2)
                .allMatch(c -> c.getManagedBy().getId().equals(supervisorA.getId()));
    }

    @Test
    @DisplayName("a candidate account cannot use the staff endpoint (Admin/Supervisor only)")
    void candidateRoleCannotCreateStaffSide() throws Exception {
        mockMvc.perform(post("/api/candidates/manage")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staffBody(null, "s6a_c_" + run + "@steg.tn", "CIN-" + run + "-CAND")))
                .andExpect(status().isForbidden());
        assertThat(liveProfiles()).noneMatch(c -> "CIN-".concat(run).concat("-CAND")
                .equals(c.getNationalIdEncrypted()));
    }

    @Test
    @DisplayName("staff creation requires a CIN and refuses a duplicate one")
    void staffCreateRequiresACinAndRefusesDuplicates() throws Exception {
        // Missing / blank / explicit null.
        mockMvc.perform(post("/api/candidates/manage")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staffBody(null, "s6a_n1_" + run + "@steg.tn", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CANDIDATE_NATIONAL_ID_REQUIRED"));
        mockMvc.perform(post("/api/candidates/manage")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staffBody(null, "s6a_n2_" + run + "@steg.tn", "   ")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CANDIDATE_NATIONAL_ID_REQUIRED"));

        String cin = "CIN-" + run + "-DUP";
        staffCreate(adminToken, null, "s6a_d1_" + run + "@steg.tn", cin, "Dup", "One");

        mockMvc.perform(post("/api/candidates/manage")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staffBody(null, "s6a_d2_" + run + "@steg.tn", cin)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CANDIDATE_NATIONAL_ID_DUPLICATE"));

        // Still exactly ONE live profile for that CIN.
        assertThat(liveProfiles().stream().filter(c -> cin.equals(c.getNationalIdEncrypted())))
                .hasSize(1);
    }

    @Test
    @DisplayName("the manager must be a back-office account")
    void managerMustBeABackOfficeUser() throws Exception {
        String cin = "CIN-" + run + "-BADMGR";
        mockMvc.perform(post("/api/candidates/manage")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staffBody(ownerAccount.getId(), "s6a_bm_" + run + "@steg.tn", cin)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CANDIDATE_MANAGER_INVALID"));

        mockMvc.perform(post("/api/candidates/manage")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staffBody(UUID.randomUUID(), "s6a_bm2_" + run + "@steg.tn", cin)))
                .andExpect(status().isNotFound());

        assertThat(liveProfiles().stream().filter(c -> cin.equals(c.getNationalIdEncrypted()))).isEmpty();
    }

    @Test
    @DisplayName("the supervisor filter matches a staff-created profile (managed_by, no internship yet)")
    void supervisorFilterMatchesStaffCreatedProfiles() throws Exception {
        String cin = "CIN-" + run + "-FILT";
        staffCreate(adminToken, supervisorA.getId(), "s6a_f_" + run + "@steg.tn", cin, "Fil", "Ter");

        MvcResult filtered = mockMvc.perform(get("/api/candidates/manage")
                        .param("supervisor", supervisorA.getId().toString())
                        .param("q", run)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = json(filtered);
        assertThat(page.get("page").get("totalElements").asInt()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("email").asText()).isEqualTo("s6a_f_" + run + "@steg.tn");

        // The same filter on the queue row supervisor column resolves to the
        // managing staff member even without an internship.
        assertThat(page.get("content").get(0).get("supervisorEmail").asText())
                .isEqualTo(supervisorA.getEmail());
    }

    @Test
    @DisplayName("creating a profile never creates or approves an application")
    void staffCreateDoesNotBypassApproval() throws Exception {
        String cin = "CIN-" + run + "-NOAPP";
        MvcResult created = staffCreate(adminToken, null, "s6a_na_" + run + "@steg.tn", cin, "No", "App");
        UUID candidateId = UUID.fromString(json(created).get("id").asText());
        assertThat(candidateId).isNotNull();

        // The legacy bare candidate list shows the profile, but no application
        // exists for it: staff creation is not an application.
        MvcResult applications = mockMvc.perform(get("/api/applications")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(applications.getResponse().getContentAsString())
                .as("no application was created for the staff-created profile")
                .doesNotContain(candidateId.toString());

        // Approval is Admin-only, and stays so for a supervisor: the endpoint
        // itself refuses him even with a valid payload (403 before the service).
        String approvalBody = objectMapper.writeValueAsString(
                Map.of("departmentId", departmentRepository.findAll().get(0).getId().toString()));
        mockMvc.perform(post("/api/applications/{id}/approve", candidateId)
                        .header("Authorization", "Bearer " + supervisorAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalBody))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Claim by the owner (audit assumption #16)
    // =========================================================================

    @Test
    @DisplayName("the owner claims the staff profile by registering with the same CIN")
    void ownerClaimsTheStaffProfileByCin() throws Exception {
        String cin = "CIN-" + run + "-CLAIM";
        staffCreate(supervisorAToken, null, "s6a_k_" + run + "@steg.tn", cin, "Claim", "Me");
        UUID staffId = liveProfileWithCin(cin).getId();

        MvcResult claimed = selfCreate(ownerToken, "s6a_k_" + run + "@steg.tn", cin);

        // 201 = same shape as a fresh registration, but the row is the SAME one.
        assertThat(claimed.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(claimed).get("nationalId").asText()).isEqualTo(cin);
        UUID claimedId = UUID.fromString(json(claimed).get("id").asText());
        assertThat(claimedId).isEqualTo(staffId);

        Candidate stored = candidatePort().findById(staffId).orElseThrow();
        assertThat(stored.getUser()).as("the account is attached on claim").isNotNull();
        assertThat(stored.getUser().getId()).isEqualTo(ownerAccount.getId());
        assertThat(stored.getManagedBy()).as("the managing staff member is preserved").isNotNull();
        assertThat(stored.getManagedBy().getId()).isEqualTo(supervisorA.getId());
        assertThat(stored.getUniversity().getId()).as("academic data entered by staff is preserved")
                .isEqualTo(university.getId());

        // Exactly one live profile for that CIN — no duplicate was created.
        assertThat(liveProfiles().stream().filter(c -> cin.equals(c.getNationalIdEncrypted())))
                .hasSize(1);
    }

    @Test
    @DisplayName("the owner claims the staff profile by registering with the same email")
    void ownerClaimsTheStaffProfileByEmail() throws Exception {
        String email = "s6a_em_" + run + "@steg.tn";
        String staffCin = "CIN-" + run + "-STAFFCIN";
        staffCreate(adminToken, null, email, staffCin, "Staff", "Typed");
        UUID staffId = liveProfileWithCin(staffCin).getId();

        // The applicant presents the same EMAIL and a different CIN: the profile
        // is claimed and the CIN is corrected (staff had mistyped it).
        String applicantCin = "CIN-" + run + "-TRUECIN";
        MvcResult claimed = selfCreate(ownerToken, email, applicantCin);

        UUID claimedId = UUID.fromString(json(claimed).get("id").asText());
        assertThat(claimedId).isEqualTo(staffId);

        Candidate stored = candidatePort().findById(staffId).orElseThrow();
        assertThat(stored.getNationalIdEncrypted()).isEqualTo(applicantCin);
        assertThat(stored.getUser().getId()).isEqualTo(ownerAccount.getId());
        assertThat(liveProfiles().stream().filter(c -> applicantCin.equals(c.getNationalIdEncrypted())))
                .hasSize(1);
    }

    @Test
    @DisplayName("a profile owned by another account is never claimable (no identity merge)")
    void claimingSomeoneElsesProfileIsRefused() throws Exception {
        String cin = "CIN-" + run + "-OWNED";
        staffCreate(adminToken, null, "s6a_o0_" + run + "@steg.tn", cin, "Owned", "Profile");
        UUID staffId = liveProfileWithCin(cin).getId();

        // First account claims it.
        selfCreate(ownerToken, "s6a_o1_" + run + "@steg.tn", cin);

        // Second account presenting the same CIN is refused, and gets no profile.
        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfBody("s6a_o2_" + run + "@steg.tn", cin)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CANDIDATE_NATIONAL_ID_DUPLICATE"));

        assertThat(candidatePort().findByUserId(otherAccount.getId())).isEmpty();
        assertThat(candidatePort().findById(staffId).orElseThrow().getUser().getId())
                .isEqualTo(ownerAccount.getId());

        // And the owner cannot claim twice either (one live profile per account).
        mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfBody("s6a_o1_" + run + "@steg.tn", cin)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CANDIDATE_PROFILE_EXISTS"));
    }

    @Test
    @DisplayName("the anonymous intake claims a staff-created profile instead of duplicating it")
    void anonymousIntakeClaimsTheStaffProfile() throws Exception {
        String cin = "CIN-" + run + "-ANON";
        String email = "s6a_anon_" + run + "@steg.tn";
        staffCreate(adminToken, null, email, cin, "Anon", "Ymous");
        UUID staffId = liveProfileWithCin(cin).getId();

        String payload = objectMapper.writeValueAsString(Map.of(
                "firstName", "Anon", "lastName", "Ymous",
                "email", email, "nationalId", cin,
                "universityId", university.getId().toString(),
                "desiredStartDate", "2026-06-01",
                "desiredEndDate", "2026-07-31"));
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/public/applications")
                        .file(new MockMultipartFile("application", "application", "application/json",
                                payload.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated());

        Candidate stored = candidatePort().findById(staffId).orElseThrow();
        assertThat(stored.getUser()).as("the intake attached an account to the staff profile").isNotNull();
        assertThat(stored.getManagedBy()).isNotNull();
        assertThat(liveProfiles().stream().filter(c -> cin.equals(c.getNationalIdEncrypted())))
                .hasSize(1);
    }

    // =========================================================================
    // Soft delete interaction
    // =========================================================================

    @Test
    @DisplayName("a soft-deleted staff profile becomes a tombstone: fresh row, empty queues")
    void softDeletedStaffProfileIsATombstone() throws Exception {
        String cin = "CIN-" + run + "-GONE";
        staffCreate(supervisorAToken, null, "s6a_g0_" + run + "@steg.tn", cin, "Gone", "Profile");
        UUID staffId = liveProfileWithCin(cin).getId();
        assertThat(liveProfileWithCin(cin).getUser()).isNull();

        // The supervisor deletes his own unclaimed profile (no dependencies).
        mockMvc.perform(delete("/api/candidates/{id}", staffId)
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isNoContent());

        // Tombstone: row kept for audit, manager link kept, hidden everywhere.
        Candidate tombstone = candidatePort().findById(staffId).orElseThrow();
        assertThat(tombstone.getDeletedAt()).isNotNull();
        assertThat(tombstone.getManagedBy()).isNotNull();
        mockMvc.perform(get("/api/candidates/{id}", staffId)
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isNotFound());

        // The person can now register with the same CIN and gets a NEW row; the
        // tombstone is never resurrected (V42 + V44).
        MvcResult fresh = selfCreate(ownerToken, "s6a_g_" + run + "@steg.tn", cin);
        UUID freshId = UUID.fromString(json(fresh).get("id").asText());
        assertThat(freshId).isNotEqualTo(staffId);

        Candidate reRegistered = candidatePort().findById(freshId).orElseThrow();
        assertThat(reRegistered.getManagedBy()).as("a fresh registration is not staff-managed").isNull();
        assertThat(liveProfiles().stream().filter(c -> cin.equals(c.getNationalIdEncrypted())))
                .hasSize(1);
    }

    @Test
    @DisplayName("a claimed staff profile keeps its manager and its scope after the claim")
    void claimedProfileStaysInTheCreatorScope() throws Exception {
        String cin = "CIN-" + run + "-KEEP";
        String email = "s6a_keep_" + run + "@steg.tn";
        staffCreate(supervisorAToken, null, email, cin, "Keep", "Scope");
        selfCreate(ownerToken, email, cin);

        UUID id = liveProfileWithCin(cin).getId();
        // Still supervised by the creator, still hidden from the other supervisor.
        assertThat(queueSize(supervisorAToken)).isEqualTo(1);
        assertThat(queueSize(supervisorBToken)).isZero();
        mockMvc.perform(get("/api/candidates/{id}", id)
                        .header("Authorization", "Bearer " + supervisorBToken))
                .andExpect(status().isNotFound());
        // And the owner now sees it on their own profile endpoint.
        mockMvc.perform(get("/api/candidates/me").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private User newUser(String prefix, String roleCode) {
        Role role = roleRepository.findByCode(roleCode).orElse(null);
        assertThat(role).as("seeded role " + roleCode).isNotNull();
        User user = new User(prefix + "_s6a_" + run + "@steg.tn", "hash", UserStatus.ACTIVE);
        user.getAssignedRoles().add(role);
        return userRepository.saveAndFlush(user);
    }

    private String token(User user, String role) {
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of(role));
    }

    private tn.steg.backend.candidate.domain.repository.CandidateRepository candidatePort() {
        return candidateRepository;
    }

    private String staffBody(UUID managedByUserId, String email, String nationalId) throws Exception {
        Map<String, Object> candidate = new HashMap<>();
        candidate.put("firstName", "Staff");
        candidate.put("lastName", "Created");
        candidate.put("email", email);
        candidate.put("universityId", university.getId().toString());
        if (nationalId != null) {
            candidate.put("nationalId", nationalId);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("candidate", candidate);
        if (managedByUserId != null) {
            body.put("managedByUserId", managedByUserId.toString());
        }
        return objectMapper.writeValueAsString(body);
    }

    private String selfBody(String email, String nationalId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Self");
        body.put("lastName", "Registered");
        body.put("email", email);
        body.put("universityId", university.getId().toString());
        if (nationalId != null) {
            body.put("nationalId", nationalId);
        }
        return objectMapper.writeValueAsString(body);
    }

    private MvcResult staffCreate(String bearer, UUID managedByUserId, String email,
                                  String nationalId, String firstName, String lastName) throws Exception {
        Map<String, Object> candidate = new HashMap<>();
        candidate.put("firstName", firstName);
        candidate.put("lastName", lastName);
        candidate.put("email", email);
        candidate.put("universityId", university.getId().toString());
        candidate.put("nationalId", nationalId);
        Map<String, Object> body = new HashMap<>();
        body.put("candidate", candidate);
        if (managedByUserId != null) {
            body.put("managedByUserId", managedByUserId.toString());
        }
        return mockMvc.perform(post("/api/candidates/manage")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private MvcResult selfCreate(String bearer, String email, String nationalId) throws Exception {
        return mockMvc.perform(post("/api/candidates")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfBody(email, nationalId)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private Candidate liveProfileWithCin(String cin) {
        return liveProfiles().stream()
                .filter(c -> cin.equals(c.getNationalIdEncrypted()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no live profile with CIN " + cin));
    }

    private List<Candidate> liveProfiles() {
        return candidateRepository.findAll().stream()
                .filter(c -> c.getDeletedAt() == null)
                .toList();
    }

    private int queueSize(String bearer) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("page").get("totalElements").asInt();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}