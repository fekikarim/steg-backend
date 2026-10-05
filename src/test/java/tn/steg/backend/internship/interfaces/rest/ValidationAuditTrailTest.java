package tn.steg.backend.internship.interfaces.rest;

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
import tn.steg.backend.audit.domain.model.AuditLog;
import tn.steg.backend.audit.domain.repository.AuditLogRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipLifecycleService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S7 pre-check (c): every validation step writes an audit row carrying actor,
 * action and entity — and no secrets. Proven against real Postgres (committed
 * transactions, like production).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("S7 — validation audit trail (Testcontainers)")
class ValidationAuditTrailTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 s7 audit file content".getBytes(StandardCharsets.UTF_8);

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private InternshipLifecycleService lifecycleService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private UserPrincipal adminPrincipal;
    private User supervisorUser;
    private University university;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElseThrow();

        adminUser = userRepository.saveAndFlush(new User("admin_au_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorUser = userRepository.saveAndFlush(new User("sup_au_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorUser.getAssignedRoles().add(supervisorRole);
        supervisorUser = userRepository.saveAndFlush(supervisorUser);

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_AU_" + uid(), "Audit Dept", "AU"));
        Employee adminEmployee = new Employee("EMP-AU-ADM-" + uid(), "Admin", "Audit", dept);
        adminEmployee.setUser(adminUser);
        employeeRepository.saveAndFlush(adminEmployee);

        university = universityRepository.saveAndFlush(new University("UNI_AU_" + uid(), "Audit Uni"));
    }

    private record Fixture(UUID internshipId, UUID internUserId, String internToken,
                           UUID deliverableId, UUID journalDeliverableId) {
    }

    private Fixture flowFixture() throws Exception {
        String tag = uid();
        User internUser = userRepository.saveAndFlush(new User("cand_au_" + tag + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(tag.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("First" + tag, "Last" + tag, internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse resp = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "Audit project", "Ingénieur", false, supervisorUser.getId()), adminPrincipal);
        lifecycleService.transition(resp.id(), InternshipStatus.IN_PROGRESS, "test", adminPrincipal);

        String internToken = jwtService.generateAccessToken(
                internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));
        MvcResult uploaded = mockMvc.perform(multipart("/api/internships/" + resp.id() + "/deliverables")
                        .file(new MockMultipartFile("file", "report.pdf", "application/pdf", PDF_BYTES))
                        .param("title", "Rapport de stage")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isCreated())
                .andReturn();
        UUID deliverableId = UUID.fromString(objectMapper.readTree(
                uploaded.getResponse().getContentAsString()).get("id").asText());
        mockMvc.perform(post("/api/internships/deliverables/" + deliverableId + "/submit")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());

        MvcResult second = mockMvc.perform(multipart("/api/internships/" + resp.id() + "/deliverables")
                        .file(new MockMultipartFile("file", "journal.pdf", "application/pdf", PDF_BYTES))
                        .param("title", "Journal de stage")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isCreated())
                .andReturn();
        UUID journalId = UUID.fromString(objectMapper.readTree(
                second.getResponse().getContentAsString()).get("id").asText());
        mockMvc.perform(post("/api/internships/deliverables/" + journalId + "/submit")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk());
        return new Fixture(resp.id(), internUser.getId(), internToken, deliverableId, journalId);
    }

    private List<AuditLog> rows(String entityType, UUID entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(entityType, entityId);
    }

    private static void assertActorActionEntity(AuditLog row, UUID actorId, String action,
                                                String entityType, UUID entityId) {
        assertThat(row.getActor()).as("audit actor for %s", action).isNotNull();
        assertThat(row.getActor().getId()).as("audit actor id for %s", action).isEqualTo(actorId);
        assertThat(row.getAction()).isEqualTo(action);
        assertThat(row.getEntityType()).isEqualTo(entityType);
        assertThat(row.getEntityId()).isEqualTo(entityId);
    }

    private void decide(UUID internshipId, String documentType, String decision, String comment) throws Exception {
        String body = comment == null
                ? "{\"documentType\":\"" + documentType + "\",\"decision\":\"" + decision + "\"}"
                : "{\"documentType\":\"" + documentType + "\",\"decision\":\"" + decision
                        + "\",\"comment\":\"" + comment + "\"}";
        mockMvc.perform(post("/api/internship-validation/" + internshipId + "/decisions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("report submission audits the deliverable and the status move with the intern as actor")
    void submissionWritesAudit() throws Exception {
        Fixture f = flowFixture();

        List<AuditLog> deliverableRows = rows("Deliverable", f.deliverableId());
        assertThat(deliverableRows.stream().map(AuditLog::getAction))
                .contains("DELIVERABLE_SUBMITTED");
        assertActorActionEntity(
                deliverableRows.stream().filter(r -> r.getAction().equals("DELIVERABLE_SUBMITTED")).findFirst()
                        .orElseThrow(),
                f.internUserId(), "DELIVERABLE_SUBMITTED", "Deliverable", f.deliverableId());

        List<AuditLog> internshipRows = rows("Internship", f.internshipId());
        AuditLog moved = internshipRows.stream()
                .filter(r -> r.getAction().equals("INTERNSHIP_STATUS_CHANGED")
                        && r.getNewValues() != null && r.getNewValues().contains("REPORT_SUBMITTED"))
                .findFirst().orElseThrow();
        assertActorActionEntity(moved, f.internUserId(), "INTERNSHIP_STATUS_CHANGED",
                "Internship", f.internshipId());
    }

    @Test
    @DisplayName("AI runs audit who/when/outcome — including degraded runs")
    void aiRunsWriteAudit() throws Exception {
        Fixture f = flowFixture();

        mockMvc.perform(post("/api/internship-validation/" + f.internshipId() + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"REPORT\"}"))
                .andExpect(status().isOk());

        List<AuditLog> internshipRows = rows("Internship", f.internshipId());
        AuditLog run = internshipRows.stream()
                .filter(r -> r.getAction().equals("AI_VERIFICATION_RUN"))
                .findFirst().orElseThrow();
        assertActorActionEntity(run, adminUser.getId(), "AI_VERIFICATION_RUN", "Internship", f.internshipId());
        assertThat(run.getNewValues()).contains("REPORT");
        // Degraded in this profile (no python-ai configured) — still audited.
        assertThat(run.getNewValues()).contains("INCONCLUSIVE");
    }

    @Test
    @DisplayName("each manual decision audits actor, decision, document and comment")
    void decisionsWriteAudit() throws Exception {
        Fixture f = flowFixture();
        decide(f.internshipId(), "REPORT", "VALIDATED", null);
        decide(f.internshipId(), "JOURNAL", "REJECTED", "Add week 4");

        List<AuditLog> internshipRows = rows("Internship", f.internshipId());
        AuditLog validated = internshipRows.stream()
                .filter(r -> r.getAction().equals("VALIDATION_DECISION_VALIDATED"))
                .findFirst().orElseThrow();
        assertActorActionEntity(validated, adminUser.getId(), "VALIDATION_DECISION_VALIDATED",
                "Internship", f.internshipId());
        assertThat(validated.getNewValues()).contains("REPORT");

        AuditLog rejected = internshipRows.stream()
                .filter(r -> r.getAction().equals("VALIDATION_DECISION_REJECTED"))
                .findFirst().orElseThrow();
        assertActorActionEntity(rejected, adminUser.getId(), "VALIDATION_DECISION_REJECTED",
                "Internship", f.internshipId());
        assertThat(rejected.getNewValues()).contains("Add week 4");
    }

    @Test
    @DisplayName("VALIDATED and receipt generation audit actor, entity and outcome; reprint audits the download")
    void validatedAndReceiptWriteAudit() throws Exception {
        Fixture f = flowFixture();
        decide(f.internshipId(), "REPORT", "VALIDATED", null);
        decide(f.internshipId(), "JOURNAL", "VALIDATED", null);

        List<AuditLog> internshipRows = rows("Internship", f.internshipId());
        AuditLog validated = internshipRows.stream()
                .filter(r -> r.getAction().equals("INTERNSHIP_STATUS_CHANGED")
                        && r.getNewValues() != null && r.getNewValues().contains("VALIDATED"))
                .findFirst().orElseThrow();
        assertActorActionEntity(validated, adminUser.getId(), "INTERNSHIP_STATUS_CHANGED",
                "Internship", f.internshipId());

        MvcResult receipt = mockMvc.perform(post("/api/internship-validation/" + f.internshipId() + "/receipt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String financeCaseId = objectMapper.readTree(receipt.getResponse().getContentAsString())
                .get("financeCaseId").asText();
        String receiptReference = objectMapper.readTree(receipt.getResponse().getContentAsString())
                .get("reference").asText();

        List<AuditLog> receiptRows = auditLogRepository
                .findByAction("PAYMENT_RECEIPT_ISSUED",
                        org.springframework.data.domain.PageRequest.of(0, 50))
                .stream()
                .filter(r -> r.getNewValues() != null && r.getNewValues().contains(receiptReference))
                .toList();
        assertThat(receiptRows).isNotEmpty();
        assertActorActionEntity(receiptRows.get(0), adminUser.getId(), "PAYMENT_RECEIPT_ISSUED",
                receiptRows.get(0).getEntityType(), receiptRows.get(0).getEntityId());

        mockMvc.perform(get("/api/finance-cases/" + financeCaseId + "/receipt")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        List<AuditLog> downloads = auditLogRepository
                .findByAction("PAYMENT_RECEIPT_DOWNLOADED",
                        org.springframework.data.domain.PageRequest.of(0, 50))
                .stream()
                .filter(r -> r.getActor() != null && adminUser.getId().equals(r.getActor().getId()))
                .toList();
        assertThat(downloads).isNotEmpty();
        assertThat(downloads.get(0).getActor().getId()).isEqualTo(adminUser.getId());
    }

    @Test
    @DisplayName("no validation audit payload carries a secret")
    void noSecretsInValidationAudit() throws Exception {
        Fixture f = flowFixture();
        decide(f.internshipId(), "REPORT", "VALIDATED", null);
        mockMvc.perform(post("/api/internship-validation/" + f.internshipId() + "/verify")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"REPORT\"}"))
                .andExpect(status().isOk());

        List<AuditLog> all = new java.util.ArrayList<>();
        all.addAll(rows("Internship", f.internshipId()));
        all.addAll(rows("Deliverable", f.deliverableId()));
        all.addAll(rows("Deliverable", f.journalDeliverableId()));
        assertThat(all).isNotEmpty();
        String[] forbidden = {"password", "temporaryPassword", "serviceToken", "X-Service-Token",
                "Bearer ", "BEGIN PRIVATE KEY", "%PDF", "JVBER"};
        for (AuditLog row : all) {
            String payload = String.valueOf(row.getOldValues()) + String.valueOf(row.getNewValues());
            for (String secret : forbidden) {
                assertThat(payload).as("audit payload of %s must not contain %s", row.getAction(), secret)
                        .doesNotContain(secret);
            }
        }
    }
}
