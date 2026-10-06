package tn.steg.backend.internship.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.common.domain.exception.ConflictException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.application.dto.ResetAccountPasswordResponse;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RefreshTokenRepository;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.application.dto.AssignCandidateRequest;
import tn.steg.backend.internship.application.dto.CreateSupervisorRequest;
import tn.steg.backend.internship.application.dto.ReassignSupervisorRequest;
import tn.steg.backend.internship.application.dto.SupervisorResponse;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.notification.application.NotificationService;
import tn.steg.backend.notification.application.port.out.EmailSender;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.messaging.application.MessagingService;
import tn.steg.backend.organization.domain.repository.DepartmentRepository;

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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupervisorManagementServiceTest {

    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private InternshipRepository internshipRepository;
    private InternshipAssignmentRepository assignmentRepository;
    private DepartmentRepository departmentRepository;
    private PasswordEncoder passwordEncoder;
    private EmailSender emailSender;
    private AuditService auditService;
    private NotificationService notificationService;
    private RefreshTokenRepository refreshTokenRepository;
    private MessagingService messagingService;
    private SupervisorManagementService service;

    private UserPrincipal admin;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        internshipRepository = mock(InternshipRepository.class);
        assignmentRepository = mock(InternshipAssignmentRepository.class);
        departmentRepository = mock(DepartmentRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        emailSender = mock(EmailSender.class);
        auditService = mock(AuditService.class);
        notificationService = mock(NotificationService.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        messagingService = mock(MessagingService.class);

        when(passwordEncoder.encode(any())).thenAnswer(inv -> "hashed:" + inv.getArgument(0));

        service = new SupervisorManagementService(
                userRepository, roleRepository, internshipRepository, assignmentRepository,
                departmentRepository, passwordEncoder, emailSender, auditService, notificationService,
                refreshTokenRepository, messagingService);

        admin = new UserPrincipal(UUID.randomUUID(), "admin@steg.tn", List.of("ADMIN"));
    }

    @Test
    void searchSupervisorsIncludesActiveAssignmentsCount() {
        UUID supId = UUID.randomUUID();
        User sup = new User("sup@steg.tn", "hash", UserStatus.ACTIVE);
        sup.setId(supId);
        sup.getAssignedRoles().add(new Role("SUPERVISOR", "Supervisor", "Desc"));

        Pageable requested = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "email"));
        when(userRepository.findSupervisorUsers(isNull(), isNull(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable passed = inv.getArgument(2);
                    return new PageImpl<>(List.of(sup), passed, 1);
                });
        when(assignmentRepository.findBySupervisorUserIdAndStatus(supId, AssignmentStatus.ACTIVE))
                .thenReturn(List.of(new InternshipAssignment(), new InternshipAssignment()));

        Page<SupervisorResponse> page = service.searchSupervisors(null, null, requested);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).email()).isEqualTo("sup@steg.tn");
        assertThat(page.getContent().get(0).activeAssignmentsCount()).isEqualTo(2);
    }

    @Test
    void searchSupervisorsLowercasesTheSearchTermAndKeepsTheStatusFilter() {
        Pageable requested = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "email"));
        when(userRepository.findSupervisorUsers(eq("%amine%"), eq(UserStatus.INACTIVE), any(Pageable.class)))
                .thenReturn(Page.empty(requested));

        service.searchSupervisors("  Amine  ", UserStatus.INACTIVE, requested);

        verify(userRepository).findSupervisorUsers(eq("%amine%"), eq(UserStatus.INACTIVE), any(Pageable.class));
    }

    @Test
    void searchSupervisorsClampsPageSizeAndFallsBackToEmailSortForUnknownProperties() {
        Pageable requested = PageRequest.of(2, 5_000, Sort.by(Sort.Direction.DESC, "passwordHash"));
        when(userRepository.findSupervisorUsers(isNull(), isNull(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable passed = inv.getArgument(2);
                    return new PageImpl<>(List.of(), passed, 0);
                });

        service.searchSupervisors(null, null, requested);

        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findSupervisorUsers(isNull(), isNull(), captor.capture());
        Pageable passed = captor.getValue();
        assertThat(passed.getPageSize()).isEqualTo(100);
        assertThat(passed.getPageNumber()).isEqualTo(2);
        assertThat(passed.getSort().getOrderFor("passwordHash")).isNull();
        assertThat(passed.getSort().getOrderFor("email")).isNotNull();
        assertThat(passed.getSort().getOrderFor("email").isAscending()).isTrue();
    }

    @Test
    void createSupervisorGeneratesRandomPasswordAndEmails() {
        CreateSupervisorRequest request = new CreateSupervisorRequest("new_sup@steg.tn");
        Role role = new Role("SUPERVISOR", "Supervisor", "Desc");

        when(userRepository.existsByEmail("new_sup@steg.tn")).thenReturn(false);
        when(roleRepository.findByCode("SUPERVISOR")).thenReturn(Optional.of(role));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        ResetAccountPasswordResponse response = service.createSupervisor(request, admin);

        assertThat(response.email()).isEqualTo("new_sup@steg.tn");
        assertThat(response.temporaryPassword()).isNotBlank();
        assertThat(response.temporaryPassword()).hasSize(20);
        assertThat(response.emailSent()).isTrue();

        verify(userRepository).save(argThat(u ->
                u.getEmail().equals("new_sup@steg.tn")
                        && u.getMustChangePassword()
                        && u.getAssignedRoles().contains(role)));

        verify(auditService).log(eq("SUPERVISOR_CREATED"), eq("User"), any(), any(), any(), eq(admin.getId()), any());
    }

    @Test
    void deleteSupervisorWithActiveAssignmentsThrowsConflict() {
        UUID supId = UUID.randomUUID();
        User sup = new User("sup@steg.tn", "hash", UserStatus.ACTIVE);
        sup.setId(supId);

        when(userRepository.findById(supId)).thenReturn(Optional.of(sup));
        when(assignmentRepository.findBySupervisorUserIdAndStatus(supId, AssignmentStatus.ACTIVE))
                .thenReturn(List.of(new InternshipAssignment()));

        assertThatThrownBy(() -> service.deleteSupervisor(supId, admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot delete supervisor with active candidate assignments")
                .extracting(ex -> ((ConflictException) ex).getErrorCode())
                .isEqualTo("SUPERVISOR_HAS_ACTIVE_ASSIGNMENTS");

        verify(userRepository, never()).delete(any());
    }

    @Test
    void assignCandidateRefusesInternshipWhoseApplicationIsNotApproved() {
        UUID supId = UUID.randomUUID();
        User sup = new User("sup@steg.tn", "hash", UserStatus.ACTIVE);
        sup.setId(supId);
        sup.getAssignedRoles().add(new Role("SUPERVISOR", "Supervisor", "Desc"));

        UUID internshipId = UUID.randomUUID();
        Candidate c = new Candidate("Alice", "Candidate", "alice@test.com", "cin", new University("TU", "TU"));
        InternshipApplication application = new InternshipApplication("APP-PENDING", c, ApplicationStatus.SUBMITTED);
        Internship internship = new Internship("INT-PENDING", c, LocalDate.now(), LocalDate.now().plusMonths(1),
                InternshipType.PERFECTIONNEMENT, InternshipRequirement.OBLIGATOIRE);
        internship.setId(internshipId);
        internship.setApplication(application);

        when(userRepository.findById(supId)).thenReturn(Optional.of(sup));
        when(internshipRepository.findById(internshipId)).thenReturn(Optional.of(internship));

        assertThatThrownBy(() ->
                service.assignCandidate(supId, new AssignCandidateRequest(internshipId, null), admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only an approved candidate")
                .extracting(ex -> ((ConflictException) ex).getErrorCode())
                .isEqualTo("CANDIDATE_NOT_APPROVED");

        verify(assignmentRepository, never()).save(any());
        verify(internshipRepository, never()).save(any());
        verify(notificationService, never()).dispatch(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void assignCandidateRefusesCancelledInternshipEvenWhenApproved() {
        UUID supId = UUID.randomUUID();
        User sup = new User("sup@steg.tn", "hash", UserStatus.ACTIVE);
        sup.setId(supId);
        sup.getAssignedRoles().add(new Role("SUPERVISOR", "Supervisor", "Desc"));

        UUID internshipId = UUID.randomUUID();
        Candidate c = new Candidate("Alice", "Candidate", "alice@test.com", "cin", new University("TU", "TU"));
        InternshipApplication application = new InternshipApplication("APP-DONE", c, ApplicationStatus.APPROVED);
        Internship internship = new Internship("INT-CANCELLED", c, LocalDate.now(), LocalDate.now().plusMonths(1),
                InternshipType.PERFECTIONNEMENT, InternshipRequirement.OBLIGATOIRE);
        internship.setId(internshipId);
        internship.setApplication(application);
        internship.setStatus(InternshipStatus.CANCELLED);

        when(userRepository.findById(supId)).thenReturn(Optional.of(sup));
        when(internshipRepository.findById(internshipId)).thenReturn(Optional.of(internship));

        assertThatThrownBy(() ->
                service.assignCandidate(supId, new AssignCandidateRequest(internshipId, null), admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("CANCELLED")
                .extracting(ex -> ((ConflictException) ex).getErrorCode())
                .isEqualTo("INTERNSHIP_NOT_ACTIVE");

        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void assignCandidateCreatesActiveAssignmentAndNotifiesSupervisor() {
        UUID supId = UUID.randomUUID();
        User sup = new User("sup@steg.tn", "hash", UserStatus.ACTIVE);
        sup.setId(supId);
        sup.getAssignedRoles().add(new Role("SUPERVISOR", "Supervisor", "Desc"));

        UUID internshipId = UUID.randomUUID();
        Candidate c = new Candidate("Alice", "Candidate", "alice@test.com", "cin", new University("TU", "TU"));
        Internship internship = new Internship("INT-ASSIGN", c, LocalDate.now(), LocalDate.now().plusMonths(1),
                InternshipType.PERFECTIONNEMENT, InternshipRequirement.OBLIGATOIRE);
        internship.setId(internshipId);

        Department dept = new Department("DEP1", "Dept 1", "D");
        dept.setId(UUID.randomUUID());

        when(userRepository.findById(supId)).thenReturn(Optional.of(sup));
        when(internshipRepository.findById(internshipId)).thenReturn(Optional.of(internship));
        when(departmentRepository.findById(dept.getId())).thenReturn(Optional.of(dept));

        service.assignCandidate(supId, new AssignCandidateRequest(internshipId, dept.getId()), admin);

        assertThat(internship.getSupervisorUser()).isEqualTo(sup);
        verify(assignmentRepository).save(argThat(a ->
                a.getSupervisorUser().equals(sup)
                        && a.getDestination().equals(dept)
                        && a.getStatus() == AssignmentStatus.ACTIVE));
        verify(notificationService).dispatch(contains("assigné"), any(), any(), eq("Internship"), eq(internshipId), eq(List.of(supId)), any());
    }

    @Test
    void reassignSupervisorClosesOldAssignmentCreatesNewAndNotifiesBoth() {
        UUID oldSupId = UUID.randomUUID();
        User oldSup = new User("oldsup@steg.tn", "hash", UserStatus.ACTIVE);
        oldSup.setId(oldSupId);
        oldSup.getAssignedRoles().add(new Role("SUPERVISOR", "Supervisor", "Desc"));

        UUID newSupId = UUID.randomUUID();
        User newSup = new User("newsup@steg.tn", "hash", UserStatus.ACTIVE);
        newSup.setId(newSupId);
        newSup.getAssignedRoles().add(new Role("SUPERVISOR", "Supervisor", "Desc"));

        UUID internshipId = UUID.randomUUID();
        Candidate c = new Candidate("Alice", "Candidate", "alice@test.com", "cin", new University("TU", "TU"));
        Internship internship = new Internship("INT-REASSIGN", c, LocalDate.now(), LocalDate.now().plusMonths(1),
                InternshipType.PERFECTIONNEMENT, InternshipRequirement.OBLIGATOIRE);
        internship.setId(internshipId);
        internship.setSupervisorUser(oldSup);

        Department dept = new Department("DEP1", "Dept 1", "D");
        InternshipAssignment oldAssignment = new InternshipAssignment();
        oldAssignment.setStatus(AssignmentStatus.ACTIVE);
        oldAssignment.setSupervisorUser(oldSup);
        oldAssignment.setDestination(dept);

        when(internshipRepository.findById(internshipId)).thenReturn(Optional.of(internship));
        when(userRepository.findById(newSupId)).thenReturn(Optional.of(newSup));
        when(assignmentRepository.findByInternshipIdAndStatus(internshipId, AssignmentStatus.ACTIVE))
                .thenReturn(Optional.of(oldAssignment));

        service.reassignSupervisor(internshipId, new ReassignSupervisorRequest(newSupId, "Medical leave"), admin);

        assertThat(oldAssignment.getStatus()).isEqualTo(AssignmentStatus.REASSIGNED);
        assertThat(oldAssignment.getEndedAt()).isNotNull();
        assertThat(internship.getSupervisorUser()).isEqualTo(newSup);

        verify(assignmentRepository).save(argThat(a ->
                a.getSupervisorUser().equals(newSup)
                        && a.getStatus() == AssignmentStatus.ACTIVE
                        && "Medical leave".equals(a.getAssignmentReason())));

        verify(auditService).log(eq("SUPERVISOR_REASSIGNED"), eq("Internship"), eq(internshipId), any(), any(), eq(admin.getId()), any());
        verify(notificationService).dispatch(contains("Réaffectation"), any(), any(), eq("Internship"), eq(internshipId), eq(List.of(oldSupId, newSupId)), any());
    }
}
