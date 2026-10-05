package tn.steg.backend.notification.interfaces.rest;

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
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.domain.service.NationalIdHasher;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.document.domain.repository.ApplicationDocumentRepository;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipLifecycleService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.notification.domain.model.Notification;
import tn.steg.backend.notification.domain.model.NotificationDelivery;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationDeliveryRepository;
import tn.steg.backend.notification.infrastructure.persistence.JpaNotificationRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pre-check (f) — RECIPIENT RESOLUTION FOR EVERY EVENT TYPE (§8.1, Testcontainers).
 *
 * <p>The other notification tests cover the lifecycle/task/application/report/
 * document-rejection events. The three event types that had NO recipient test
 * are proven here, so every {@code NotificationEventListener} handler has a
 * named Testcontainers test asserting WHO the delivery rows are for:
 *
 * <ul>
 *   <li>{@code onInternshipAssigned} → intern AND supervisor (assignment is the
 *       authority; the supervisor id comes from the assignment row);</li>
 *   <li>{@code onDocumentVerified} → the candidate whose application document
 *       was verified (candidate link, never the verifier);</li>
 *   <li>{@code onFinalEvaluationRequired} → role fan-out to every active
 *       ADMIN (proved against Postgres, complementing the mocked
 *       {@code NotificationRoleFanOutTest}).</li>
 * </ul>
 *
 * <p>No {@code @Transactional}: the listeners run BEFORE_COMMIT, so each probe
 * must commit for the delivery rows to exist (same discipline as the other
 * notification integration tests).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Pre-check (f) — recipient resolution per event type (Testcontainers)")
class NotificationRecipientCoverageIntegrationTest {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4 recipient coverage file content".getBytes(StandardCharsets.UTF_8);

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private InternshipLifecycleService lifecycleService;
    @Autowired private InternshipApplicationRepository applicationRepository;
    @Autowired private ApplicationDocumentRepository applicationDocumentRepository;
    @Autowired private JpaNotificationRepository notificationRepository;
    @Autowired private JpaNotificationDeliveryRepository deliveryRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private UserPrincipal adminPrincipal;
    private User supervisorUser;
    private Employee supervisorEmployee;
    private Department department;
    private University university;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElseThrow();

        adminUser = userRepository.saveAndFlush(
                new User("admin_nrc_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorUser = userRepository.saveAndFlush(
                new User("sup_nrc_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorUser.getAssignedRoles().add(supervisorRole);
        supervisorUser = userRepository.saveAndFlush(supervisorUser);

        department = departmentRepository.saveAndFlush(
                new Department("DIR_NRC_" + uid(), "Recipient Coverage Dept", "NRC"));
        supervisorEmployee = new Employee("EMP-NRC-" + uid(), "Coverage", "Supervisor", department);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        university = universityRepository.saveAndFlush(new University("UNI_NRC_" + uid(), "NRC University"));
    }

    private record Fixture(UUID internshipId, UUID internUserId, String internToken) {
    }

    private Fixture internshipWithIntern() throws Exception {
        String tag = uid();
        User internUser = userRepository.saveAndFlush(
                new User("cand_nrc_" + tag + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(tag.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Cover" + tag, "Intern", internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("NRC" + tag);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "Recipient coverage project", "Ingénieur", false), adminPrincipal);
        String internToken = jwtService.generateAccessToken(
                internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));
        return new Fixture(created.id(), internUser.getId(), internToken);
    }

    /** title -> recipient ids of every delivery row attached to that notification. */
    private Map<String, Set<UUID>> recipientsByTitle(String entityType, UUID entityId) {
        Map<String, Set<UUID>> out = new HashMap<>();
        for (Notification notification : notificationRepository
                .findByRelatedEntityTypeAndRelatedEntityIdOrderByCreatedAtAsc(entityType, entityId)) {
            Set<UUID> recipients = new HashSet<>();
            for (NotificationDelivery delivery : deliveryRepository.findByNotificationId(notification.getId())) {
                if (delivery.getRecipient() != null) {
                    recipients.add(delivery.getRecipient().getId());
                }
            }
            out.merge(notification.getTitle(), recipients, (a, b) -> {
                a.addAll(b);
                return a;
            });
        }
        return out;
    }

    @Test
    @DisplayName("assignment notifies BOTH the intern and the supervisor — never a stranger")
    void assignmentNotifiesInternAndSupervisor() throws Exception {
        Fixture f = internshipWithIntern();

        internshipService.assign(f.internshipId(), new InternshipAssignmentRequest(
                department.getId(), supervisorEmployee.getId(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), "recipient coverage"), adminPrincipal);

        Map<String, Set<UUID>> recipients = recipientsByTitle("Internship", f.internshipId());
        assertThat(recipients.get("Internship assigned"))
                .as("the intern is told about his own assignment")
                .containsExactly(f.internUserId());
        assertThat(recipients.get("New intern assigned"))
                .as("the supervisor who was assigned is told about the intern")
                .containsExactly(supervisorUser.getId());
        // Every recipient of every assignment notification is one of those two
        // (one delivery row per resolved channel, hence the set comparison).
        assertThat(recipients.values()).allSatisfy(ids ->
                assertThat(ids).isSubsetOf(f.internUserId(), supervisorUser.getId()));
        assertThat(recipients.values().stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder(f.internUserId(), supervisorUser.getId());
    }

    @Test
    @DisplayName("document verification notifies the CANDIDATE whose document it was, not the verifier")
    void documentVerificationNotifiesTheCandidate() throws Exception {
        String suffix = uid();
        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "firstName", "Verify", "lastName", "Candidate" + suffix,
                "email", "anon_nrc_" + suffix + "@steg.com",
                "nationalId", "NRC" + suffix,
                "universityId", university.getId().toString(),
                "desiredStartDate", "2026-07-01", "desiredEndDate", "2026-07-30"));
        MvcResult submitted = mockMvc.perform(multipart("/api/public/applications")
                        .file(new MockMultipartFile("application", "application", "application/json",
                                payload.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("documents", "demande-stage.pdf",
                                "application/pdf", PDF_BYTES)))
                .andExpect(status().isCreated())
                .andReturn();
        String trackingToken = objectMapper.readTree(
                submitted.getResponse().getContentAsString()).get("trackingToken").asText();

        var application = applicationRepository
                .findByTrackingTokenHash(NationalIdHasher.sha256Hex(trackingToken))
                .orElseThrow();
        UUID applicationId = application.getId();
        // Read the identifiers through the repositories: the entity graph is
        // lazy and this test is deliberately not @Transactional.
        UUID candidateUserId = ((tn.steg.backend.candidate.domain.repository.CandidateRepository) candidateRepository)
                .findById(application.getCandidate().getId())
                .orElseThrow()
                .getUser()
                .getId();
        UUID documentId = applicationDocumentRepository.findByApplicationId(applicationId).get(0)
                .getDocument().getId();

        mockMvc.perform(put("/api/applications/" + applicationId + "/documents/" + documentId + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"VERIFIED\"}"))
                .andExpect(status().isOk());

        Map<String, Set<UUID>> recipients = recipientsByTitle("ApplicationDocument", documentId);
        assertThat(recipients).isNotEmpty();
        String verifiedTitle = recipients.keySet().stream()
                .filter(title -> title.startsWith("Document ") && title.endsWith(" verified"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no document-verified notification, titles: " + recipients.keySet()));
        assertThat(recipients.get(verifiedTitle)).containsExactly(candidateUserId);
        // The Admin who verified must not receive their own administrative notification.
        assertThat(recipients.get(verifiedTitle)).doesNotContain(adminUser.getId());
    }

    @Test
    @DisplayName("an internship validated without a FINAL evaluation alerts every active ADMIN")
    void validatedWithoutFinalEvaluationAlertsAdmins() throws Exception {
        Fixture f = internshipWithIntern();
        lifecycleService.transition(f.internshipId(), InternshipStatus.IN_PROGRESS,
                "recipient coverage: work started", adminPrincipal);

        for (String title : List.of("Rapport de stage", "Journal de stage")) {
            MvcResult uploaded = mockMvc.perform(
                            multipart("/api/internships/" + f.internshipId() + "/deliverables")
                                    .file(new MockMultipartFile("file", "doc.pdf", "application/pdf", PDF_BYTES))
                                    .param("title", title)
                                    .header("Authorization", "Bearer " + f.internToken()))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID deliverableId = UUID.fromString(objectMapper.readTree(
                    uploaded.getResponse().getContentAsString()).get("id").asText());
            mockMvc.perform(post("/api/internships/deliverables/" + deliverableId + "/submit")
                            .header("Authorization", "Bearer " + f.internToken()))
                    .andExpect(status().isOk());
        }
        decide(f.internshipId(), "REPORT");
        decide(f.internshipId(), "JOURNAL");

        Map<String, Set<UUID>> recipients = recipientsByTitle("Internship", f.internshipId());
        assertThat(recipients.get("Final evaluation required"))
                .as("the Admin role owns the validation queue and must be alerted")
                .contains(adminUser.getId());
        assertThat(recipients.get("Final evaluation required"))
                .as("the intern and the supervisor are not part of the admin fan-out for this event")
                .doesNotContain(f.internUserId(), supervisorUser.getId());
    }

    private void decide(UUID internshipId, String documentType) throws Exception {
        mockMvc.perform(post("/api/internship-validation/" + internshipId + "/decisions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"" + documentType + "\",\"decision\":\"VALIDATED\"}"))
                .andExpect(status().isOk());
    }
}