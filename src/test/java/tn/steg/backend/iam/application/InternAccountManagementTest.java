package tn.steg.backend.iam.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ConflictException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.application.dto.CreateInternAccountRequest;
import tn.steg.backend.iam.application.dto.InternAccountResponse;
import tn.steg.backend.iam.application.dto.ResetAccountPasswordResponse;
import tn.steg.backend.iam.application.dto.UpdateInternAccountRequest;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.notification.application.port.out.EmailSender;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternAccountManagementTest {

    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private CandidateRepository candidateRepository;
    private InternshipRepository internshipRepository;
    private InternshipAssignmentRepository assignmentRepository;
    private PasswordEncoder passwordEncoder;
    private EmailSender emailSender;
    private AuditService auditService;
    private InternAccountManagementService service;

    private UserPrincipal admin;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        candidateRepository = mock(CandidateRepository.class);
        internshipRepository = mock(InternshipRepository.class);
        assignmentRepository = mock(InternshipAssignmentRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        emailSender = mock(EmailSender.class);
        auditService = mock(AuditService.class);

        when(passwordEncoder.encode(any())).thenAnswer(inv -> "hashed:" + inv.getArgument(0));

        service = new InternAccountManagementService(
                userRepository, roleRepository, candidateRepository, internshipRepository,
                assignmentRepository, passwordEncoder, emailSender, auditService,
                mock(tn.steg.backend.iam.domain.repository.RefreshTokenRepository.class));

        admin = new UserPrincipal(UUID.randomUUID(), "admin@steg.tn", List.of("ADMIN"));
    }

    @Test
    void createAccountGeneratesPasswordHashesSetsMustChangeAndEmails() {
        CreateInternAccountRequest request = new CreateInternAccountRequest(
                "intern@example.com", "INTERN", null, "Intern Alice");

        Role internRole = new Role("INTERN", "Intern Role", "Description");
        when(userRepository.existsByEmail("intern@example.com")).thenReturn(false);
        when(roleRepository.findByCode("INTERN")).thenReturn(Optional.of(internRole));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        ResetAccountPasswordResponse response = service.createAccount(request, admin);

        assertThat(response.email()).isEqualTo("intern@example.com");
        assertThat(response.temporaryPassword()).isNotBlank();
        assertThat(response.temporaryPassword()).hasSize(20);
        assertThat(response.emailSent()).isTrue();

        verify(userRepository).save(argThat(u ->
                u.getEmail().equals("intern@example.com")
                        && u.getMustChangePassword()
                        && u.getPasswordHash().startsWith("hashed:")
                        && u.getAssignedRoles().contains(internRole)));

        verify(auditService).log(eq("ACCOUNT_CREATED"), eq("User"), any(), any(), any(), eq(admin.getId()), any());
    }

    @Test
    void createAccountRefusesDuplicateEmail() {
        CreateInternAccountRequest request = new CreateInternAccountRequest(
                "existing@example.com", "INTERN", null, null);
        when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createAccount(request, admin))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createAccountRefusesInvalidRole() {
        CreateInternAccountRequest request = new CreateInternAccountRequest(
                "user@example.com", "ADMIN", null, null);
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);

        assertThatThrownBy(() -> service.createAccount(request, admin))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Only INTERN or SUPERVISOR");
    }

    @Test
    void deleteSupervisorWithActiveAssignmentsThrowsConflict() {
        UUID supervisorId = UUID.randomUUID();
        User supervisor = new User("sup@steg.tn", "hash", UserStatus.ACTIVE);
        supervisor.setId(supervisorId);
        Role supervisorRole = new Role("SUPERVISOR", "Supervisor", "Description");
        supervisor.getAssignedRoles().add(supervisorRole);

        when(userRepository.findById(supervisorId)).thenReturn(Optional.of(supervisor));
        when(assignmentRepository.findBySupervisorUserIdAndStatus(supervisorId, AssignmentStatus.ACTIVE))
                .thenReturn(List.of(new InternshipAssignment()));

        assertThatThrownBy(() -> service.deleteAccount(supervisorId, admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot delete supervisor with active candidate assignments")
                .extracting(ex -> ((ConflictException) ex).getErrorCode())
                .isEqualTo("SUPERVISOR_HAS_ACTIVE_ASSIGNMENTS");

        verify(userRepository, never()).delete(any());
    }

    @Test
    void deleteUserWithoutAssignmentsSucceedsAndUnlinksCandidate() {
        UUID internUserId = UUID.randomUUID();
        User internUser = new User("intern@steg.tn", "hash", UserStatus.ACTIVE);
        internUser.setId(internUserId);

        Candidate candidate = new Candidate("Alice", "Test", "intern@steg.tn", "hash-c", new University("TU", "TU"));
        candidate.setUser(internUser);

        when(userRepository.findById(internUserId)).thenReturn(Optional.of(internUser));
        when(candidateRepository.findByUserId(internUserId)).thenReturn(Optional.of(candidate));

        service.deleteAccount(internUserId, admin);

        assertThat(candidate.getUser()).isNull();
        verify(candidateRepository).save(candidate);
        verify(userRepository).delete(internUser);
        verify(auditService).log(eq("ACCOUNT_DELETED"), eq("User"), eq(internUserId), any(), any(), eq(admin.getId()), any());
    }

    @Test
    void resetPasswordGeneratesNewPasswordAndSetsMustChange() {
        UUID userId = UUID.randomUUID();
        User user = new User("user@steg.tn", "oldHash", UserStatus.ACTIVE);
        user.setId(userId);
        user.setMustChangePassword(false);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        ResetAccountPasswordResponse response = service.resetPassword(userId, admin);

        assertThat(response.email()).isEqualTo("user@steg.tn");
        assertThat(response.temporaryPassword()).isNotBlank();
        assertThat(response.temporaryPassword()).hasSize(20);
        assertThat(user.getMustChangePassword()).isTrue();
        assertThat(user.getPasswordHash()).startsWith("hashed:");

        verify(auditService).log(eq("ACCOUNT_PASSWORD_RESET"), eq("User"), eq(userId), any(), any(), eq(admin.getId()), any());
    }

    @Test
    void listAccountsFiltersAndEnrichesCandidateData() {
        UUID internId = UUID.randomUUID();
        User intern = new User("intern@test.com", "hash", UserStatus.ACTIVE);
        intern.setId(internId);
        intern.getAssignedRoles().add(new Role("INTERN", "Intern", "Description"));

        Candidate candidate = new Candidate("Bob", "Intern", "intern@test.com", "hash-cin", new University("TU", "TU"));
        candidate.setId(UUID.randomUUID());

        Internship internship = new Internship("INT-123", candidate, LocalDate.now(), LocalDate.now().plusMonths(1),
                InternshipType.PERFECTIONNEMENT, InternshipRequirement.OBLIGATOIRE);
        internship.setId(UUID.randomUUID());

        when(userRepository.findAll()).thenReturn(List.of(intern));
        when(candidateRepository.findByUserId(internId)).thenReturn(Optional.of(candidate));
        when(internshipRepository.findByCandidateId(candidate.getId())).thenReturn(List.of(internship));

        List<InternAccountResponse> result = service.listAccounts("INTERN", UserStatus.ACTIVE, "Bob");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).email()).isEqualTo("intern@test.com");
        assertThat(result.get(0).fullName()).isEqualTo("Bob Intern");
        assertThat(result.get(0).candidateId()).isEqualTo(candidate.getId());
        assertThat(result.get(0).internshipId()).isEqualTo(internship.getId());
    }
}
