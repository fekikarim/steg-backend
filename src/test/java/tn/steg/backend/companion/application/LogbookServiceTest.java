package tn.steg.backend.companion.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.domain.model.Logbook;
import tn.steg.backend.companion.domain.model.LogbookStatus;
import tn.steg.backend.companion.domain.repository.LogbookRepository;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LogbookService} lifecycle rules.
 *
 * <p>Pure Mockito tests (no Spring context). Verifies:
 * intern-only submit, supervisor-only validate/reject, and the observable
 * state transitions DRAFT → SUBMITTED → VALIDATED/REJECTED.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LogbookService")
class LogbookServiceTest {

    private static final UUID INTERN_USER_ID = UUID.randomUUID();
    private static final UUID SUPERVISOR_USER_ID = UUID.randomUUID();
    private static final UUID OUTSIDER_ID = UUID.randomUUID();
    private static final UUID INTERNSHIP_ID = UUID.randomUUID();
    private static final UUID LOGBOOK_ID = UUID.randomUUID();

    private LogbookService service;

    private Internship internship;
    private Logbook savedLogbook;

    @Mock private LogbookRepository logbookRepository;
    @Mock private InternshipRepository internshipRepository;
    @Mock private InternshipAssignmentRepository assignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;

    private UserPrincipal internPrincipal;
    private UserPrincipal supervisorPrincipal;
    private UserPrincipal outsiderPrincipal;

    @BeforeEach
    void setUp() {
        service = new LogbookService(logbookRepository, internshipRepository,
                assignmentRepository, userRepository, auditService);

        internPrincipal = new UserPrincipal(INTERN_USER_ID, "intern@steg.tn", List.of("ROLE_INTERN"));
        supervisorPrincipal = new UserPrincipal(SUPERVISOR_USER_ID, "sup@steg.tn", List.of("ROLE_SUPERVISOR"));
        outsiderPrincipal = new UserPrincipal(OUTSIDER_ID, "outsider@steg.tn", List.of("ROLE_INTERN"));

        User internUser = new User("intern@steg.tn", "hash", UserStatus.ACTIVE);
        internUser.setId(INTERN_USER_ID);
        Candidate candidate = new Candidate("Foulen", "Ben Foulen", "intern@steg.tn", "hash", null);
        candidate.setUser(internUser);

        internship = new Internship("LB-REF-1", candidate,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1),
                InternshipType.OBSERVATION, InternshipRequirement.OBLIGATOIRE);
        internship.setId(INTERNSHIP_ID);
        internship.setStatus(InternshipStatus.COMPLETED);

        User supUser = new User("sup@steg.tn", "hash", UserStatus.ACTIVE);
        supUser.setId(SUPERVISOR_USER_ID);
        Employee supervisor = new Employee("LB-EMP-1", "Super", "Visor", new Department("LB-DEV", "Development", "d"));
        supervisor.setUser(supUser);
        InternshipAssignment assignment = new InternshipAssignment(
                internship, null, supervisor, null,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 7, 1), AssignmentStatus.ACTIVE);
        lenient().when(assignmentRepository.findByInternshipIdAndStatus(INTERNSHIP_ID, AssignmentStatus.ACTIVE))
                .thenReturn(Optional.of(assignment));

        lenient().when(internshipRepository.findById(INTERNSHIP_ID)).thenReturn(Optional.of(internship));
        lenient().when(userRepository.findById(INTERN_USER_ID)).thenReturn(Optional.of(internUser));
        lenient().when(userRepository.findById(SUPERVISOR_USER_ID)).thenReturn(Optional.of(supUser));

        savedLogbook = new Logbook(null, internship, internUser, null);
        savedLogbook.setId(LOGBOOK_ID);
    }

    private void shipInternshipNotFoundException() {
        when(internshipRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("submitForValidation")
    class Submit {

        @Test
        @DisplayName("The assigned intern can submit and the logbook moves to SUBMITTED")
        void internSubmits() {
            when(logbookRepository.findByInternshipId(INTERNSHIP_ID)).thenReturn(Optional.empty());
            when(logbookRepository.save(any(Logbook.class))).thenAnswer(inv -> inv.getArgument(0));

            Logbook result = service.submitForValidation(INTERNSHIP_ID, "  Mon carnet relu.  ", internPrincipal);

            assertThat(result.getStatus()).isEqualTo(LogbookStatus.SUBMITTED);
            assertThat(result.getFinalText()).isEqualTo("Mon carnet relu.");
            assertThat(result.getSubmittedAt()).isNotNull();
            verify(auditService).log("LOGBOOK_SUBMITTED", "Logbook",
                    result.getId(), null, java.util.Map.of("internshipId", INTERNSHIP_ID, "status", "SUBMITTED"),
                    INTERN_USER_ID, null);
        }

        @Test
        @DisplayName("A non-assigned user cannot submit")
        void outsiderCannotSubmit() {
            assertThatThrownBy(() -> service.submitForValidation(INTERNSHIP_ID, "text", outsiderPrincipal))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("Only the assigned intern");
            verify(logbookRepository, never()).save(any());
        }

        @Test
        @DisplayName("Only completed internships accept a logbook")
        void nonCompletedRejected() {
            internship.setStatus(InternshipStatus.ACTIVE);
            assertThatThrownBy(() -> service.submitForValidation(INTERNSHIP_ID, "text", internPrincipal))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("completed");
        }

        @Test
        @DisplayName("Empty text is rejected")
        void emptyTextRejected() {
            assertThatThrownBy(() -> service.submitForValidation(INTERNSHIP_ID, "   ", internPrincipal))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        @DisplayName("Re-submission after rejection is allowed and resets to SUBMITTED")
        void resubmitAfterRejection() {
            savedLogbook.setStatus(LogbookStatus.REJECTED);
            when(logbookRepository.findByInternshipId(INTERNSHIP_ID)).thenReturn(Optional.of(savedLogbook));
            when(logbookRepository.save(any(Logbook.class))).thenAnswer(inv -> inv.getArgument(0));

            Logbook result = service.submitForValidation(INTERNSHIP_ID, "new version", internPrincipal);

            assertThat(result.getStatus()).isEqualTo(LogbookStatus.SUBMITTED);
            assertThat(result.getFinalText()).isEqualTo("new version");
        }
    }

    @Nested
    @DisplayName("validate / reject")
    class Validation {

        private Logbook submitted() {
            Logbook l = new Logbook(null, internship, null, null);
            l.setId(LOGBOOK_ID);
            l.setStatus(LogbookStatus.SUBMITTED);
            return l;
        }

        @Test
        @DisplayName("The assigned supervisor can validate a SUBMITTED logbook")
        void supervisorValidates() {
            Logbook l = submitted();
            when(logbookRepository.findById(LOGBOOK_ID)).thenReturn(Optional.of(l));
            when(logbookRepository.save(any(Logbook.class))).thenAnswer(inv -> inv.getArgument(0));

            Logbook result = service.validate(LOGBOOK_ID, supervisorPrincipal);

            assertThat(result.getStatus()).isEqualTo(LogbookStatus.VALIDATED);
            assertThat(result.getValidatedAt()).isNotNull();
            assertThat(result.getValidatedBy().getId()).isEqualTo(SUPERVISOR_USER_ID);
            verify(auditService).log("LOGBOOK_VALIDATED", "Logbook",
                    LOGBOOK_ID, null, java.util.Map.of("status", "VALIDATED"),
                    SUPERVISOR_USER_ID, null);
        }

        @Test
        @DisplayName("The intern cannot validate their own logbook")
        void internCannotValidate() {
            when(logbookRepository.findById(LOGBOOK_ID)).thenReturn(Optional.of(submitted()));

            assertThatThrownBy(() -> service.validate(LOGBOOK_ID, internPrincipal))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("assigned supervisor");
            verify(logbookRepository, never()).save(any());
        }

        @Test
        @DisplayName("A non-SUBMITTED logbook cannot be validated")
        void cannotValidateDraft() {
            Logbook draft = new Logbook(null, internship, null, null);
            draft.setId(LOGBOOK_ID);
            draft.setStatus(LogbookStatus.DRAFT);
            when(logbookRepository.findById(LOGBOOK_ID)).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> service.validate(LOGBOOK_ID, supervisorPrincipal))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("SUBMITTED");
        }

        @Test
        @DisplayName("Rejection requires a reason and moves the logbook to REJECTED")
        void rejectRequiresReasonThenRejects() {
            Logbook l = submitted();
            when(logbookRepository.findById(LOGBOOK_ID)).thenReturn(Optional.of(l));
            when(logbookRepository.save(any(Logbook.class))).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() -> service.reject(LOGBOOK_ID, "  ", supervisorPrincipal))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("reason");

            Logbook rejected = service.reject(LOGBOOK_ID, "Dates à revoir", supervisorPrincipal);
            assertThat(rejected.getStatus()).isEqualTo(LogbookStatus.REJECTED);
            assertThat(rejected.getRejectionReason()).isEqualTo("Dates à revoir");
            verify(auditService).log("LOGBOOK_REJECTED", "Logbook",
                    LOGBOOK_ID, null, java.util.Map.of("reason", "Dates à revoir"),
                    SUPERVISOR_USER_ID, null);
        }

        @Test
        @DisplayName("A REJECTED logbook is immutable to the supervisor until resubmitted")
        void cannotValidateOrRejectAfterRejection() {
            Logbook l = submitted();
            l.setStatus(LogbookStatus.REJECTED);
            when(logbookRepository.findById(LOGBOOK_ID)).thenReturn(Optional.of(l));

            assertThatThrownBy(() -> service.validate(LOGBOOK_ID, supervisorPrincipal))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("SUBMITTED");
            assertThatThrownBy(() -> service.reject(LOGBOOK_ID, "again", supervisorPrincipal))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("SUBMITTED");
            verify(logbookRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("promoteToOfficial")
    class Official {

        private Logbook validated() {
            Logbook l = new Logbook(null, internship, null, null);
            l.setId(LOGBOOK_ID);
            l.setStatus(LogbookStatus.VALIDATED);
            l.setValidatedAt(Instant.now());
            return l;
        }

        @Test
        @DisplayName("HR promotes a VALIDATED logbook to OFFICIAL")
        void hrPromotes() {
            UserPrincipal hr = new UserPrincipal(UUID.randomUUID(), "hr@steg.tn", List.of("ROLE_HR"));
            when(logbookRepository.findById(LOGBOOK_ID)).thenReturn(Optional.of(validated()));
            when(logbookRepository.save(any(Logbook.class))).thenAnswer(inv -> inv.getArgument(0));

            Logbook result = service.promoteToOfficial(LOGBOOK_ID, hr);

            assertThat(result.getStatus()).isEqualTo(LogbookStatus.OFFICIAL);
            verify(auditService).log("LOGBOOK_OFFICIALIZED", "Logbook",
                    LOGBOOK_ID, null, java.util.Map.of("status", "OFFICIAL"),
                    hr.getId(), null);
        }

        @Test
        @DisplayName("A supervisor cannot finalize the logbook as official")
        void supervisorCannotPromote() {
            when(logbookRepository.findById(LOGBOOK_ID)).thenReturn(Optional.of(validated()));

            assertThatThrownBy(() -> service.promoteToOfficial(LOGBOOK_ID, supervisorPrincipal))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("HR/ADMIN");
            verify(logbookRepository, never()).save(any());
        }

        @Test
        @DisplayName("Only VALIDATED logbooks can be made official")
        void onlyValidatedCanPromote() {
            UserPrincipal hr = new UserPrincipal(UUID.randomUUID(), "hr@steg.tn", List.of("ROLE_HR"));
            Logbook submitted = new Logbook(null, internship, null, null);
            submitted.setId(LOGBOOK_ID);
            submitted.setStatus(LogbookStatus.SUBMITTED);
            when(logbookRepository.findById(LOGBOOK_ID)).thenReturn(Optional.of(submitted));

            assertThatThrownBy(() -> service.promoteToOfficial(LOGBOOK_ID, hr))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("VALIDATED");
            verify(logbookRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("authorization shortcuts")
    class Authorization {

        @Test
        @DisplayName("ADMIN can validate without an explicit assignment")
        void adminValidates() {
            UserPrincipal admin = new UserPrincipal(UUID.randomUUID(), "admin@steg.tn", List.of("ROLE_ADMIN"));
            when(logbookRepository.findById(LOGBOOK_ID)).thenReturn(Optional.of(submitted()));
            when(logbookRepository.save(any(Logbook.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.findById(admin.getId())).thenReturn(
                    Optional.of(new User("admin@steg.tn", "hash", UserStatus.ACTIVE)));

            Logbook result = service.validate(LOGBOOK_ID, admin);

            assertThat(result.getStatus()).isEqualTo(LogbookStatus.VALIDATED);
        }

        private Logbook submitted() {
            Logbook l = new Logbook(null, internship, null, null);
            l.setId(LOGBOOK_ID);
            l.setStatus(LogbookStatus.SUBMITTED);
            return l;
        }
    }
}