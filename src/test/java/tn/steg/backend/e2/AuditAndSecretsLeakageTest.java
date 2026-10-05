package tn.steg.backend.e2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.audit.domain.repository.AuditLogRepository;
import tn.steg.backend.notification.domain.repository.NotificationRepository;
import tn.steg.backend.iam.application.InternAccountManagementService;
import tn.steg.backend.iam.application.dto.CreateInternAccountRequest;
import tn.steg.backend.iam.application.dto.ResetAccountPasswordResponse;
import tn.steg.backend.internship.application.SupervisorManagementService;
import tn.steg.backend.internship.application.dto.CreateSupervisorRequest;
import tn.steg.backend.workflow.application.MobileAccountProvisioningService;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("E2 - Audit never stores secrets")
class AuditAndSecretsLeakageTest {

    @Autowired
    private SupervisorManagementService supervisorManagementService;

    @Autowired
    private InternAccountManagementService internAccountManagementService;

    @Autowired
    private MobileAccountProvisioningService mobileAccountProvisioningService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private CandidateRepository candidateRepository;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private tn.steg.backend.iam.infrastructure.persistence.UserRepository userRepository;

    @Test
    @DisplayName("One-time passwords returned in response never appear in audit logs, notifications or entity columns")
    void testNoSecretsInAuditOrNotifications() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String supervisorEmail = "sup_" + suffix + "@steg.tn";
        String internEmail = "intern_" + suffix + "@steg.tn";

        departmentRepository.saveAndFlush(new Department("DEPT_SEC_" + suffix, "Security Dept", "IT"));

        // Collect all plaintext passwords that were returned via DTOs
        List<String> capturedPasswords = new ArrayList<>();

        // 1. Create a supervisor — response includes temporaryPassword
        CreateSupervisorRequest supReq = new CreateSupervisorRequest(supervisorEmail);
        ResetAccountPasswordResponse supResp = supervisorManagementService.createSupervisor(supReq, null);
        assertThat(supResp.temporaryPassword()).isNotBlank().hasSize(20);
        capturedPasswords.add(supResp.temporaryPassword());

        // 2. Create intern account — response includes temporaryPassword
        CreateInternAccountRequest internReq = new CreateInternAccountRequest(internEmail, "INTERN", null, null);
        ResetAccountPasswordResponse internResp = internAccountManagementService.createAccount(internReq, null);
        assertThat(internResp.temporaryPassword()).isNotBlank().hasSize(20);
        capturedPasswords.add(internResp.temporaryPassword());

        // 3. Reset the intern's password — response includes temporaryPassword
        UUID internUserId = internAccountManagementService.listAccounts("INTERN", null, internEmail).get(0).id();
        ResetAccountPasswordResponse resetResp = internAccountManagementService.resetPassword(internUserId, null);
        assertThat(resetResp.temporaryPassword()).isNotBlank().hasSize(20);
        capturedPasswords.add(resetResp.temporaryPassword());

        // 4. Provision mobile account — result includes temporaryPassword
        University uni = universityRepository.saveAndFlush(new University("UNI_SEC_" + suffix, "Security Uni"));
        Candidate cand = candidateRepository.saveAndFlush(
                new Candidate("Sec", "Intern", "sec_intern_" + suffix + "@steg.tn", "hash", uni));

        Internship internship = new Internship(
                "INT-SEC-" + suffix, cand, LocalDate.now(), LocalDate.now().plusMonths(2),
                InternshipType.PFE, InternshipRequirement.OBLIGATOIRE
        );
        MobileAccountProvisioningService.ProvisioningResult provResult =
                mobileAccountProvisioningService.provisionIfRequired(internship).orElseThrow();
        assertThat(provResult.temporaryPassword()).isNotBlank().hasSize(20);
        capturedPasswords.add(provResult.temporaryPassword());

        // === AUDIT LOGS SCAN ===
        List<java.util.Map<String, Object>> allLogs = jdbcTemplate.queryForList("SELECT * FROM audit_logs");

        for (var log : allLogs) {
            StringBuilder details = new StringBuilder();
            for (var entry : log.entrySet()) {
                if (entry.getValue() != null) {
                    details.append(entry.getValue().toString()).append(" ");
                }
            }
            String detailStr = details.toString();

            // No column should contain any of the captured plaintext passwords
            for (String password : capturedPasswords) {
                assertThat(detailStr)
                        .as("Audit log should not contain plaintext password '%s'", password)
                        .doesNotContain(password);
            }

            // Generic checks
            assertThat(detailStr).doesNotContain("passwordHash");
            assertThat(detailStr).doesNotContain("temporaryPassword");
            assertThat(detailStr).doesNotContain("eyJ"); // JWT
        }

        // === NOTIFICATION ROWS SCAN ===
        List<java.util.Map<String, Object>> allNotifs = jdbcTemplate.queryForList("SELECT * FROM notifications");
        for (var notif : allNotifs) {
            StringBuilder details = new StringBuilder();
            for (var entry : notif.entrySet()) {
                if (entry.getValue() != null) {
                    details.append(entry.getValue().toString()).append(" ");
                }
            }
            String detailStr = details.toString();

            for (String password : capturedPasswords) {
                assertThat(detailStr)
                        .as("Notification should not contain plaintext password '%s'", password)
                        .doesNotContain(password);
            }

            assertThat(detailStr).doesNotContain("passwordHash");
            assertThat(detailStr).doesNotContain("eyJ");
        }

        // === LOG SCANNING (Application Logs) ===
        // Get the list of all log messages captured during the test
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> listAppender = new ch.qos.logback.core.read.ListAppender<>();
        listAppender.start();
        rootLogger.addAppender(listAppender);

        try {
            // Trigger some operations that might log
            internAccountManagementService.listAccounts("INTERN", null, null);
            supervisorManagementService.getSupervisor(userRepository.findByEmail(supResp.email()).get().getId());

            // Scan all captured logs
            for (ch.qos.logback.classic.spi.ILoggingEvent event : listAppender.list) {
                String msg = event.getFormattedMessage();
                if (msg != null) {
                    for (String password : capturedPasswords) {
                        assertThat(msg)
                                .as("Application log should not contain plaintext password")
                                .doesNotContain(password);
                    }
                    assertThat(msg).doesNotContain("eyJ"); // No JWTs in logs
                }
            }
        } finally {
            rootLogger.detachAppender(listAppender);
        }
    }
}
