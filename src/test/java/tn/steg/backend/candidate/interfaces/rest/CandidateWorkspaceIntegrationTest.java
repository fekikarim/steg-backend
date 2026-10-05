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
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.infrastructure.persistence.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.infrastructure.persistence.TaskRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Persistence-backed proof for the Candidates workspace (AGENTS.md §5.1 / §6.2,
 * docs/legacy-removal.md #7):
 *
 * <ul>
 *   <li>the staff queue runs pagination, search, every §5.1 filter and sort
 *       SERVER-side against real Postgres (soft-deleted rows excluded),</li>
 *   <li>scope is derived server-side: Admin all, Supervisor only his own
 *       candidates, CANDIDATE refused 403,</li>
 *   <li>the detail aggregate arrives in ONE backend call (profile, supervisor,
 *       applications, internship, task summary, documents, certificate/receipt),</li>
 *   <li>soft delete is refused with 409 CANDIDATE_HAS_DEPENDENCIES while the
 *       candidate has applications/internships/tasks/receipts and otherwise
 *       removes the profile from every list and detail (404 afterwards),</li>
 *   <li>403-where-404 is gone: out-of-scope reads, updates and deletes answer
 *       404 (no existence leak), in-scope Supervisor updates work without ever
 *       receiving the CIN.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Candidate workspace persistence (Testcontainers)")
class CandidateWorkspaceIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipAssignmentRepository assignmentRepository;
    @Autowired private InternshipApplicationRepository applicationRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    private String run;
    private User admin;
    private User supervisorA;
    private User supervisorB;
    private User candidateUser;
    private Department department;
    private University universityA;
    private University universityB;

    private Candidate candidateA1;
    private Candidate candidateA2;
    private Candidate candidateB1;
    private Candidate candidateClean;
    private Candidate candidateTask;
    private Internship internshipA1;

    private String adminToken;
    private String supervisorAToken;
    private String supervisorBToken;
    private String candidateToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        run = UUID.randomUUID().toString().substring(0, 8);

        admin = newUser("admin", "ADMIN");
        supervisorA = newUser("supa", "SUPERVISOR");
        supervisorB = newUser("supb", "SUPERVISOR");
        candidateUser = newUser("cand", "CANDIDATE");
        adminToken = token(admin, "ROLE_ADMIN");
        supervisorAToken = token(supervisorA, "ROLE_SUPERVISOR");
        supervisorBToken = token(supervisorB, "ROLE_SUPERVISOR");
        candidateToken = token(candidateUser, "ROLE_CANDIDATE");

        department = departmentRepository.saveAndFlush(
                new Department("DEPT_CW_" + run, "Dept CW " + run, "IT"));
        universityA = universityRepository.saveAndFlush(
                new University("UNI_CW_A_" + run, "Workspace University A " + run));
        universityB = universityRepository.saveAndFlush(
                new University("UNI_CW_B_" + run, "Workspace University B " + run));

        // A1: account enabled, SUBMITTED/PFE application, supervised via the
        // DIRECT internship link (scenario shape 1), one task on the internship.
        candidateA1 = newCandidate("a1", "Alpha", universityA, enabledLinkedUser("a1"));
        application("A1", candidateA1, ApplicationStatus.SUBMITTED, InternshipType.PFE);
        internshipA1 = supervisedInternship("INT-CW-A1-", candidateA1, InternshipType.PFE, supervisorA);
        taskRepository.saveAndFlush(new Task(internshipA1, admin, "Workspace task " + run, "details"));

        // A2: NO linked account, RESUBMITTED/PERFECTIONNEMENT, direct link.
        candidateA2 = newCandidate("a2", "Amina", universityA, null);
        application("A2", candidateA2, ApplicationStatus.RESUBMITTED, InternshipType.PERFECTIONNEMENT);
        supervisedInternship("INT-CW-A2-", candidateA2, InternshipType.PERFECTIONNEMENT, supervisorA);

        // B1: account DISABLED, REJECTED/PFE, supervised ONLY through a
        // user-backed assignment row (internship.supervisorUser stays NULL).
        candidateB1 = newCandidate("b1", "Bravo", universityB, disabledLinkedUser("b1"));
        application("B1", candidateB1, ApplicationStatus.REJECTED, InternshipType.PFE);
        Internship internshipB1 = newInternship("INT-CW-B1-", candidateB1, InternshipType.PFE);
        activeAssignment(internshipB1, supervisorB);

        // Clean: no account, no application, no internship → deletable.
        candidateClean = newCandidate("cln", "Clean", universityA, null);

        // Task-only: internship + task but no application → delete blocked on tasks.
        candidateTask = newCandidate("tsk", "Tasks", universityA, null);
        Internship internshipTask = newInternship("INT-CW-TSK-", candidateTask, InternshipType.OBSERVATION);
        taskRepository.saveAndFlush(new Task(internshipTask, admin, "Blocked task " + run, "details"));
    }

    // =========================================================================
    // Queue: paging, search, filters, sort
    // =========================================================================

    @Test
    @DisplayName("the literal /manage route wins and the queue is paged server-side")
    void manageListIsPagedServerSide() throws Exception {
        // Literal segment is never swallowed by /{id} (would 404/parse-fail).
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("size", "2")
                        .param("page", "0")
                        .param("sort", "email,asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.page.totalPages").value(3))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].email").value("cw_a1_" + run + "@steg.tn"))
                .andExpect(jsonPath("$.content[1].email").value("cw_a2_" + run + "@steg.tn"));

        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("size", "2")
                        .param("page", "2")
                        .param("sort", "email,asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].email").value("cw_tsk_" + run + "@steg.tn"));
    }

    @Test
    @DisplayName("search, university, type, status, validation and date-range filters all run server-side")
    void filtersNarrowTheQueueServerSide() throws Exception {
        // Full-name search through the university join (no row dropped).
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", "a1-" + run)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(candidateA1.getId().toString()));

        // University (exact, case-insensitive).
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("university", universityA.getName())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(4));

        // Internship type: internship link OR application calculated type.
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("type", "PFE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));

        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("type", "OBSERVATION")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(candidateTask.getId().toString()));

        // Application status (EXISTS semantics).
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("status", "SUBMITTED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(candidateA1.getId().toString()));

        // Validation status = linked-account state (§5.1 "validation status").
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("validation", "ACTIVE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(candidateA1.getId().toString()))
                .andExpect(jsonPath("$.content[0].accountEnabled").value(true));

        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("validation", "DISABLED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(4));

        // Profile creation date range: everything was created during this test.
        // Day bounds resolve in the application time zone (default
        // Africa/Tunis, see ApplicationTimeZone), so the probes use the Tunis
        // calendar day — a UTC day would exclude rows created after 23:00 UTC
        // that still belong to "today" in Tunis.
        java.time.LocalDate tunisToday =
                java.time.LocalDate.now(java.time.ZoneId.of("Africa/Tunis"));
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("from", tunisToday.minusDays(1).toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(5));

        // …and nothing exists after the window / before it.
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("from", tunisToday.plusDays(2).toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("to", tunisToday.minusDays(2).toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("a from-filter evaluated at 00:30 Tunis time still returns rows created at 00:10 that day")
    void tunisDayBoundaryIncludesEarlyMorningRows() throws Exception {
        java.time.ZoneId tunis = java.time.ZoneId.of("Africa/Tunis");
        java.time.LocalDate tunisToday = java.time.LocalDate.now(tunis);
        // 00:10 Tunis time = 23:10 UTC the previous day: under a UTC
        // day-boundary this row would fall outside from=today.
        java.time.Instant tenPastMidnightTunis =
                tunisToday.atTime(0, 10).atZone(tunis).toInstant();

        Candidate early = new Candidate(
                "Early" + run, "Morning",
                ("early.morning." + run + "@example.com").toLowerCase(),
                tn.steg.backend.candidate.domain.service.NationalIdHasher.sha256Hex("CIN-EARLY-" + run),
                universityA);
        early.setUser(candidateUser);
        early = candidateRepository.saveAndFlush(early);
        // created_at is updatable=false, so the back-date goes through SQL:
        // the filter (not the fixture clock) is what this test exercises.
        jdbcTemplate.update("UPDATE candidates SET created_at = ?, updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(tenPastMidnightTunis),
                java.sql.Timestamp.from(tenPastMidnightTunis), early.getId());

        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", "Early" + run)
                        .param("from", tunisToday.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(early.getId().toString()));

        // The same row is outside the Tunis "yesterday" window's upper bound.
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", "Early" + run)
                        .param("to", tunisToday.minusDays(1).toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("the supervisor filter matches BOTH supervision shapes; sort is whitelisted and size clamped")
    void supervisorFilterSortAndClamp() throws Exception {
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("supervisor", supervisorA.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));

        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("supervisor", supervisorB.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(candidateB1.getId().toString()));

        // Row enrichment: application count/status, account state, supervisor.
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("sort", "email,asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].applicationCount").value(1))
                .andExpect(jsonPath("$.content[0].latestApplicationStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.content[0].supervisorEmail").value(supervisorA.getEmail()))
                .andExpect(jsonPath("$.content[1].accountEnabled").value(nullValue()))
                .andExpect(jsonPath("$.content[2].accountEnabled").value(false))
                .andExpect(jsonPath("$.content[2].supervisorEmail").value(supervisorB.getEmail()))
                .andExpect(jsonPath("$.content[3].applicationCount").value(0))
                .andExpect(jsonPath("$.content[3].latestApplicationStatus").value(nullValue()));

        // Unknown sort key falls back instead of failing; oversized pages clamp.
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("sort", "passwordHash,asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(5));

        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("size", "500")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(100));
    }

    // =========================================================================
    // Scope (§3.4 / §6.2)
    // =========================================================================

    @Test
    @DisplayName("Supervisor sees only his own candidates, whatever the client sends; CANDIDATE is 403")
    void scopeIsResolvedServerSide() throws Exception {
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("sort", "email,asc")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(candidateA1.getId().toString()))
                .andExpect(jsonPath("$.content[1].id").value(candidateA2.getId().toString()));

        // Client-supplied scope hints must be ignored: the server re-derives it.
        // (`candidateId` is not a queue parameter at all; the supervisor filter
        // above is a real filter, ANDed with the server-side scope.)
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .param("candidateId", candidateB1.getId().toString())
                        .param("supervisorUserId", supervisorB.getId().toString())
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));

        mockMvc.perform(get("/api/candidates/manage")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Detail aggregate (§5.1 "one backend call")
    // =========================================================================

    @Test
    @DisplayName("overview returns profile, supervisor, applications, internship, tasks and documents in ONE call")
    void overviewAggregatesEverythingInOneCall() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/candidates/" + candidateA1.getId() + "/overview")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("profile").get("nationalId").asText()).isEqualTo("CIN-" + run + "-A1");
        assertThat(body.get("supervisor").get("email").asText()).isEqualTo(supervisorA.getEmail());
        assertThat(body.get("applications")).hasSize(1);
        assertThat(body.get("applications").get(0).get("reference").asText())
                .isEqualTo("APP-CW-" + run + "-A1");
        assertThat(body.get("internship").get("reference").asText())
                .isEqualTo("INT-CW-A1-" + run);
        assertThat(body.get("tasks").get("total").asInt()).isEqualTo(1);
        assertThat(body.get("tasks").get("byStatus").get("TODO").asLong()).isEqualTo(1L);
        assertThat(body.get("documents")).isEmpty();
        assertThat(body.get("certificate").isNull()).isTrue();
        assertThat(body.get("receipt").isNull()).isTrue();

        // The assignment-only shape resolves the supervisor too.
        mockMvc.perform(get("/api/candidates/" + candidateB1.getId() + "/overview")
                        .header("Authorization", "Bearer " + supervisorBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supervisor.email").value(supervisorB.getEmail()))
                .andExpect(jsonPath("$.profile.nationalId").value(nullValue()));

        // Out of scope → 404 (never 403), and the CIN never reaches a Supervisor.
        mockMvc.perform(get("/api/candidates/" + candidateB1.getId() + "/overview")
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/candidates/" + candidateA1.getId() + "/overview")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Soft delete (§5.1)
    // =========================================================================

    @Test
    @DisplayName("delete is refused with 409 while the candidate has applications")
    void deleteIsBlockedWhenApplicationsExist() throws Exception {
        mockMvc.perform(delete("/api/candidates/" + candidateA1.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CANDIDATE_HAS_DEPENDENCIES"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("applications")));

        // Still visible afterwards — nothing was deleted.
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(5));
    }

    @Test
    @DisplayName("delete is refused with 409 while the candidate has tasks (internship without application)")
    void deleteIsBlockedWhenTasksExist() throws Exception {
        mockMvc.perform(delete("/api/candidates/" + candidateTask.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CANDIDATE_HAS_DEPENDENCIES"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("tasks")));
    }

    @Test
    @DisplayName("a clean candidate is soft-deleted and disappears from every list and detail (404)")
    void softDeleteRemovesTheCandidateEverywhere() throws Exception {
        mockMvc.perform(delete("/api/candidates/" + candidateClean.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        // Excluded from the paged queue…
        mockMvc.perform(get("/api/candidates/manage")
                        .param("q", run)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(4));

        // …from the legacy staff list…
        assertThat(legacyListIds(adminToken)).doesNotContain(candidateClean.getId());

        // …and from every single-resource read: 404, not 403.
        mockMvc.perform(get("/api/candidates/" + candidateClean.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/candidates/" + candidateClean.getId() + "/overview")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/candidates/" + candidateClean.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        // The CIN hash stays unique: the row is kept (soft delete), just hidden.
        assertThat(candidatePort().findById(candidateClean.getId())).isPresent();
        assertThat(candidatePort().findById(candidateClean.getId()).get().getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("a Supervisor cannot delete outside his scope (404, no existence leak) and Candidates are 403")
    void deleteIsScopedAndRoleProtected() throws Exception {
        // Foreign candidate → 404 before any dependency check.
        mockMvc.perform(delete("/api/candidates/" + candidateB1.getId())
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isNotFound());

        // Not supervised at all → 404 for a Supervisor too.
        mockMvc.perform(delete("/api/candidates/" + candidateClean.getId())
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/candidates/" + candidateClean.getId())
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Scoped update (docs/legacy-removal.md #7: 403-where-404)
    // =========================================================================

    @Test
    @DisplayName("out-of-scope reads and updates answer 404 (never 403)")
    void outOfScopeAccessIsNotFound() throws Exception {
        // Supervisor A reading/updating Supervisor B's candidate.
        mockMvc.perform(get("/api/candidates/" + candidateB1.getId())
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/candidates/" + candidateB1.getId())
                        .contentType("application/json")
                        .content(updateBody(candidateB1, "Renamed", null))
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isNotFound());

        // A foreign candidate user: 404 instead of the legacy 403.
        mockMvc.perform(put("/api/candidates/" + candidateA1.getId())
                        .contentType("application/json")
                        .content(updateBody(candidateA1, "Renamed", null))
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an in-scope Supervisor updates his own candidate without ever receiving the CIN")
    void supervisorUpdatesOwnCandidateWithoutCin() throws Exception {
        mockMvc.perform(put("/api/candidates/" + candidateA1.getId())
                        .contentType("application/json")
                        .content(updateBody(candidateA1, "Renamed By Supervisor", null))
                        .header("Authorization", "Bearer " + supervisorAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Renamed By Supervisor"))
                // Redaction: the update response never carries the CIN to a Supervisor.
                .andExpect(jsonPath("$.nationalId").value(nullValue()));

        // The change is real and the stored CIN was kept (blank = keep).
        MvcResult adminRead = mockMvc.perform(get("/api/candidates/" + candidateA1.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(adminRead.getResponse().getContentAsString());
        assertThat(body.get("firstName").asText()).isEqualTo("Renamed By Supervisor");
        assertThat(body.get("nationalId").asText()).isEqualTo("CIN-" + run + "-A1");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private User newUser(String prefix, String roleCode) {
        Role role = roleRepository.findByCode(roleCode).orElse(null);
        assertThat(role).as("seeded role " + roleCode).isNotNull();
        User user = new User(prefix + "_cw_" + run + "@steg.tn", "hash", UserStatus.ACTIVE);
        user.getAssignedRoles().add(role);
        return userRepository.saveAndFlush(user);
    }

    private User enabledLinkedUser(String label) {
        User user = new User("cw_" + label + "_" + run + "@steg.tn", "hash", UserStatus.ACTIVE);
        user.setEnabled(true);
        return userRepository.saveAndFlush(user);
    }

    private User disabledLinkedUser(String label) {
        User user = new User("cw_" + label + "_" + run + "@steg.tn", "hash", UserStatus.ACTIVE);
        user.setEnabled(false);
        return userRepository.saveAndFlush(user);
    }

    private String token(User user, String role) {
        return jwtService.generateAccessToken(user.getId(), user.getEmail(), List.of(role));
    }

    private Candidate newCandidate(String label, String firstName, University university, User linkedUser) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String cin = "CIN-" + run + "-" + label.toUpperCase();
            String hash = Base64.getEncoder().encodeToString(
                    digest.digest(cin.getBytes(StandardCharsets.UTF_8)));
            Candidate candidate = new Candidate(
                    firstName,
                    label.toUpperCase() + "-" + run,
                    "cw_" + label + "_" + run + "@steg.tn",
                    hash,
                    university);
            candidate.setNationalIdEncrypted(cin);
            candidate.setUser(linkedUser);
            return candidateRepository.saveAndFlush(candidate);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private InternshipApplication application(String label, Candidate candidate, ApplicationStatus status,
                                               InternshipType type) {
        InternshipApplication app = new InternshipApplication(
                "APP-CW-" + run + "-" + label, candidate, status);
        app.setCalculatedType(type);
        app.setSubmissionDate(LocalDate.now());
        app.setSubmittedOnline(true);
        return applicationRepository.saveAndFlush(app);
    }

    private Internship newInternship(String referencePrefix, Candidate candidate, InternshipType type) {
        Internship internship = new Internship(referencePrefix + run, candidate,
                LocalDate.now(), LocalDate.now().plusMonths(2),
                type, InternshipRequirement.OBLIGATOIRE);
        internship.setStatus(tn.steg.backend.internship.domain.model.InternshipStatus.APPROVED);
        return internshipRepository.saveAndFlush(internship);
    }

    /** Direct-link supervision shape (internship.supervisorUser set). */
    private Internship supervisedInternship(String referencePrefix, Candidate candidate, InternshipType type,
                                            User supervisorUser) {
        Internship internship = newInternship(referencePrefix, candidate, type);
        internship.setSupervisorUser(supervisorUser);
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

    /** Infra repos extend both JpaRepository and the domain port — cast for the port view. */
    private tn.steg.backend.candidate.domain.repository.CandidateRepository candidatePort() {
        return candidateRepository;
    }

    private tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository assignmentPort() {
        return assignmentRepository;
    }

    private List<UUID> legacyListIds(String bearer) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/candidates")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<UUID> ids = new ArrayList<>();
        array.forEach(node -> ids.add(UUID.fromString(node.get("id").asText())));
        return ids;
    }

    private String updateBody(Candidate candidate, String firstName, String nationalId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", candidate.getLastName());
        body.put("email", candidate.getEmail());
        body.put("universityId", candidate.getUniversity().getId().toString());
        if (nationalId != null) {
            body.put("nationalId", nationalId);
        }
        return objectMapper.writeValueAsString(body);
    }
}
