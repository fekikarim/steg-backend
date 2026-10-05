package tn.steg.backend.audit.interfaces.rest;

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
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.audit.domain.model.AuditSource;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S9 audit viewer search (§8.2, Testcontainers): every filter combines
 * server-side in one paged query — action, entity type + id, actor, source
 * and date range — and the endpoint stays Admin-only.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("S9 — audit search (Testcontainers)")
class AuditSearchIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JwtService jwtService;
    @Autowired private AuditService auditService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private User supervisorUser;
    private String supervisorToken;
    private UUID entityA;
    private UUID entityB;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static volatile boolean seeded;
    private static volatile UUID seededSupervisorId;
    // Fixed entities: @BeforeEach re-runs per test, but the seed rows must
    // exist exactly once for the count assertions to hold.
    private static final UUID ENTITY_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENTITY_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElseThrow();

        adminUser = userRepository.saveAndFlush(new User("admin_as_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorUser = userRepository.saveAndFlush(new User("sup_as_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorUser.getAssignedRoles().add(supervisorRole);
        supervisorUser = userRepository.saveAndFlush(supervisorUser);
        supervisorToken = jwtService.generateAccessToken(
                supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        UUID entityA = ENTITY_A;
        UUID entityB = ENTITY_B;
        // @BeforeEach runs per test but the rows must exist exactly once;
        // the actor assertion below must use the SEEDED actor, not this
        // test's fresh user row.
        if (!seeded) {
            auditService.log("S9_SEARCH_ALPHA", "Internship", entityA, null,
                    java.util.Map.of("step", 1), adminUser.getId(), null, null, null, AuditSource.BACK_OFFICE);
            auditService.log("S9_SEARCH_ALPHA", "Candidate", entityB, null,
                    java.util.Map.of("step", 2), supervisorUser.getId(), null, null, null, AuditSource.MOBILE);
            auditService.log("S9_SEARCH_BETA", "Internship", entityA, null,
                    java.util.Map.of("step", 3), adminUser.getId(), null, null, null, AuditSource.AI);
            seededSupervisorId = supervisorUser.getId();
            seeded = true;
        }
        this.entityA = entityA;
        this.entityB = entityB;
    }

    private long total(String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/audit" + query)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("page").path("totalElements").asLong();
    }

    @Test
    @DisplayName("every filter combines server-side in one query")
    void filtersCombine() throws Exception {
        UUID entityA = this.entityA;
        UUID entityB = this.entityB;

        assertThat(total("?action=S9_SEARCH_ALPHA")).isEqualTo(2);
        assertThat(total("?action=S9_SEARCH_ALPHA&entityType=Candidate")).isEqualTo(1);
        assertThat(total("?action=S9_SEARCH_ALPHA&actorId=" + seededSupervisorId)).isEqualTo(1);
        assertThat(total("?entityId=" + entityA)).isEqualTo(2);
        assertThat(total("?source=MOBILE&action=S9_SEARCH_ALPHA")).isEqualTo(1);
        assertThat(total("?source=AI&action=S9_SEARCH_BETA")).isEqualTo(1);
        assertThat(total("?source=BACK_OFFICE&action=S9_SEARCH_ALPHA&entityId=" + entityA)).isEqualTo(1);

        // Date range: everything was just written, so "since yesterday" keeps all three.
        String since = java.time.Instant.now().minusSeconds(3600).toString();
        assertThat(total("?action=S9_SEARCH_ALPHA&from=" + since)).isEqualTo(2);
        // ...but "until yesterday" matches nothing new.
        String until = java.time.Instant.now().minusSeconds(86400 * 30).toString();
        assertThat(total("?entityId=" + entityB + "&to=" + until)).isZero();

        // Unknown action is an empty page, not an error.
        assertThat(total("?action=S9_SEARCH_NOPE")).isZero();
    }

    @Test
    @DisplayName("calendar-day bounds resolve in the application time zone (Africa/Tunis)")
    void tunisCalendarDayBounds() throws Exception {
        java.time.ZoneId tunis = java.time.ZoneId.of("Africa/Tunis");
        java.time.LocalDate tunisToday = java.time.LocalDate.now(tunis);
        // 00:10 Tunis time = 23:10 UTC the previous day.
        java.time.Instant tenPastMidnightTunis =
                tunisToday.atTime(0, 10).atZone(tunis).toInstant();
        String action = "S9_TZ_" + uid();

        auditService.log(action, "Candidate", UUID.randomUUID(), null,
                java.util.Map.of("step", 9), adminUser.getId(), null, null, null, AuditSource.BACK_OFFICE);
        jdbcTemplate.update("UPDATE audit_logs SET created_at = ?, updated_at = ? WHERE action = ?",
                java.sql.Timestamp.from(tenPastMidnightTunis),
                java.sql.Timestamp.from(tenPastMidnightTunis), action);

        // fromDate=today (Tunis) includes the 00:10 row ...
        assertThat(total("?action=" + action + "&fromDate=" + tunisToday)).isEqualTo(1);
        // ... while toDate=yesterday (Tunis) excludes it.
        assertThat(total("?action=" + action + "&toDate=" + tunisToday.minusDays(1))).isZero();
        // Absolute instants keep their exact semantics alongside the new params.
        String justBefore = tenPastMidnightTunis.minusSeconds(60).toString();
        assertThat(total("?action=" + action + "&from=" + justBefore)).isEqualTo(1);
        String justAfter = tenPastMidnightTunis.plusSeconds(3600).toString();
        assertThat(total("?action=" + action + "&from=" + justAfter)).isZero();
    }

    @Test
    @DisplayName("viewer stays Admin-only; supervisor gets 403 with no rows")
    void adminOnly() throws Exception {
        mockMvc.perform(get("/api/audit?action=S9_SEARCH_ALPHA")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("detail carries the source channel")
    void detailCarriesSource() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/audit?action=S9_SEARCH_BETA")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].source").value("AI"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }
}
