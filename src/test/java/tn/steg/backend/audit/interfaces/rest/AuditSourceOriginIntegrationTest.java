package tn.steg.backend.audit.interfaces.rest;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.audit.domain.model.AuditLog;
import tn.steg.backend.audit.domain.model.AuditSource;
import tn.steg.backend.audit.domain.repository.AuditLogRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pre-check (e) — AUDIT SOURCE PROVENANCE (AGENTS.md §8.2, Testcontainers).
 *
 * <p>{@code AuditSearchIntegrationTest} proves the SOURCE FILTER works on rows
 * seeded directly through {@code AuditService.log}. This test proves the
 * writers themselves: each origin channel is driven through its REAL endpoint
 * and the persisted {@code audit_logs.source} column is asserted:
 *
 * <ul>
 *   <li>mobile-endpoint action (intern deliverable create + submit via
 *       {@code /api/internships/...}) → {@code MOBILE};</li>
 *   <li>front-office-endpoint action (anonymous intake via public
 *       {@code /api/public/applications}) → {@code FRONT_OFFICE};</li>
 *   <li>AI-run action (admin advisory verification via
 *       {@code /api/internship-validation/{id}/verify}) → {@code AI}.</li>
 * </ul>
 *
 * <p>Proven against real Postgres (committed rows, like production) — a
 * persistence-dependent audit row only flips to OK with a test like this.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Pre-check (e) — audit source provenance via real endpoints (Testcontainers)")
class AuditSourceOriginIntegrationTest {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4 audit origin source file content".getBytes(StandardCharsets.UTF_8);

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository universityRepository;
    @Autowired private tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository candidateRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private InternshipApplicationRepository applicationRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private UserPrincipal adminPrincipal;
    private University university;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();

        adminUser = userRepository.saveAndFlush(
                new User("admin_aso_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        university = universityRepository.saveAndFlush(new University("UNI_ASO_" + uid(), "Audit Origin Uni"));
    }

    /** Intern + candidate + internship (admin falls back as supervisor) + intern token. */
    private record Fixture(UUID internshipId, UUID internUserId, String internToken) {
    }

    private Fixture internshipWithIntern() throws Exception {
        String tag = uid();
        User internUser = userRepository.saveAndFlush(
                new User("cand_aso_" + tag + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(tag.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Origin" + tag, "Mobile", internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("ASO" + tag);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "Audit origin project", "Ingénieur", false), adminPrincipal);
        String internToken = jwtService.generateAccessToken(
                internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));
        return new Fixture(created.id(), internUser.getId(), internToken);
    }

    /** Uploads a deliverable as the intern and returns its id. */
    private UUID uploadDeliverable(UUID internshipId, String internToken) throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/api/internships/" + internshipId + "/deliverables")
                        .file(new MockMultipartFile("file", "report.pdf", "application/pdf", PDF_BYTES))
                        .param("title", "Audit origin report")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(
                uploaded.getResponse().getContentAsString()).get("id").asText());
    }

    private AuditLog row(String action, String entityType, UUID entityId) {
        List<AuditLog> rows = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(entityType, entityId);
        assertThat(rows.stream().map(AuditLog::getAction))
                .as("audit rows for %s on %s/%s", action, entityType, entityId)
                .contains(action);
        return rows.stream().filter(r -> r.getAction().equals(action)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("mobile-endpoint actions (intern deliverable create + submit) audit with source MOBILE")
    void mobileEndpointWritesMobileSource() throws Exception {
        Fixture f = internshipWithIntern();

        UUID deliverableId = uploadDeliverable(f.internshipId(), f.internToken());
        mockMvc.perform(post("/api/internships/deliverables/" + deliverableId + "/submit")
                        .header("Authorization", "Bearer " + f.internToken()))
                .andExpect(status().isOk());

        AuditLog created = row("DELIVERABLE_CREATED", "Deliverable", deliverableId);
        assertThat(created.getSource()).isEqualTo(AuditSource.MOBILE);
        assertThat(created.getActor()).isNotNull();
        assertThat(created.getActor().getId()).isEqualTo(f.internUserId());

        AuditLog submitted = row("DELIVERABLE_SUBMITTED", "Deliverable", deliverableId);
        assertThat(submitted.getSource()).isEqualTo(AuditSource.MOBILE);
    }

    @Test
    @DisplayName("mobile task status update audits with source MOBILE (BR-55)")
    void mobileTaskStatusUpdateWritesMobileSource() throws Exception {
        Fixture f = internshipWithIntern();
        MvcResult createdTask = mockMvc.perform(post("/api/internships/" + f.internshipId() + "/tasks")
                        .header("Authorization", "Bearer " + f.internToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Mobile test task\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID taskId = UUID.fromString(objectMapper.readTree(
                createdTask.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/internships/tasks/" + taskId + "/status")
                        .param("status", "IN_PROGRESS")
                        .header("Authorization", "Bearer " + f.internToken()))
                .andExpect(status().isOk());

        AuditLog statusLog = row("COMPANION_TASK_STATUS_CHANGED", "Task", taskId);
        assertThat(statusLog.getSource()).isEqualTo(AuditSource.MOBILE);
        assertThat(statusLog.getActor()).isNotNull();
        assertThat(statusLog.getActor().getId()).isEqualTo(f.internUserId());
    }

    @Test
    @DisplayName("front-office-endpoint actions (anonymous intake) audit with source FRONT_OFFICE")
    void frontOfficeEndpointWritesFrontOfficeSource() throws Exception {
        String suffix = uid();
        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "firstName", "Front", "lastName", "Office" + suffix,
                "email", "anon_aso_" + suffix + "@steg.com",
                "nationalId", "ASO" + suffix,
                "universityId", university.getId().toString(),
                "desiredStartDate", "2026-07-01", "desiredEndDate", "2026-07-30"));
        MvcResult result = mockMvc.perform(multipart("/api/public/applications")
                        .file(new MockMultipartFile("application", "application", "application/json",
                                payload.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("documents", "demande-stage.pdf", "application/pdf", PDF_BYTES)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").isNotEmpty())
                .andReturn();
        String trackingToken = objectMapper.readTree(
                result.getResponse().getContentAsString()).get("trackingToken").asText();

        UUID applicationId = applicationRepository
                .findByTrackingTokenHash(tn.steg.backend.candidate.domain.service.NationalIdHasher
                        .sha256Hex(trackingToken))
                .orElseThrow()
                .getId();

        AuditLog submitted = row("ANONYMOUS_APPLICATION_SUBMITTED", "InternshipApplication", applicationId);
        assertThat(submitted.getSource()).isEqualTo(AuditSource.FRONT_OFFICE);
    }

    @Test
    @DisplayName("AI runs audit with source AI (advisory verify, degraded in this profile)")
    void aiRunWritesAiSource() throws Exception {
        Fixture f = internshipWithIntern();
        UUID deliverableId = uploadDeliverable(f.internshipId(), f.internToken());
        mockMvc.perform(post("/api/internships/deliverables/" + deliverableId + "/submit")
                        .header("Authorization", "Bearer " + f.internToken()))
                .andExpect(status().isOk());

        // Advisory AI verification (python-ai unconfigured in the test profile →
        // degraded INCONCLUSIVE result, still audited).
        mockMvc.perform(post("/api/internship-validation/" + f.internshipId() + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"REPORT\"}"))
                .andExpect(status().isOk());

        AuditLog run = row("AI_VERIFICATION_RUN", "Internship", f.internshipId());
        assertThat(run.getSource()).isEqualTo(AuditSource.AI);
        assertThat(run.getActor()).isNotNull();
        assertThat(run.getActor().getId()).isEqualTo(adminUser.getId());
        assertThat(run.getNewValues()).contains("REPORT");
    }
}
