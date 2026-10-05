package tn.steg.backend.internship.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.organization.domain.model.Employee;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupervisionScopeServiceTest {

    private InternshipAssignmentRepository assignmentRepository;
    private tn.steg.backend.internship.domain.repository.InternshipRepository internshipRepository;
    private CandidateRepository candidateRepository;
    private SupervisionScopeService scopeService;
    private UUID internshipId;
    private UUID adminId;
    private UUID supervisorId;
    private UUID otherSupervisorId;

    @BeforeEach
    void setUp() {
        assignmentRepository = mock(InternshipAssignmentRepository.class);
        internshipRepository = mock(tn.steg.backend.internship.domain.repository.InternshipRepository.class);
        candidateRepository = mock(CandidateRepository.class);
        scopeService = new SupervisionScopeService(assignmentRepository, internshipRepository, candidateRepository);
        internshipId = UUID.randomUUID();
        adminId = UUID.randomUUID();
        supervisorId = UUID.randomUUID();
        otherSupervisorId = UUID.randomUUID();
    }

    /**
     * S6a: a staff-created candidate has no internship yet, so its scope comes
     * from candidates.managed_by_user_id (V44). The rule lives HERE and nowhere
     * else (§3.2).
     */
    @Test
    void aCandidateManagedByTheActorIsInsideHisScope() {
        UUID candidateId = UUID.randomUUID();
        when(candidateRepository.existsByIdAndManagedByIdAndDeletedAtIsNull(candidateId, supervisorId))
                .thenReturn(true);

        UserPrincipal supervisor = new UserPrincipal(supervisorId, "supervisor@example.test", List.of("SUPERVISOR"));
        UserPrincipal other = new UserPrincipal(otherSupervisorId, "other@example.test", List.of("SUPERVISOR"));

        assertTrue(scopeService.supervisesCandidate(supervisor, candidateId));
        assertFalse(scopeService.supervisesCandidate(other, candidateId));
    }

    @Test
    void supervisedCandidateIdsUnionManagedCandidatesWithAssignments() {
        UUID managedId = UUID.randomUUID();
        Candidate managed = new Candidate();
        managed.setId(managedId);
        when(candidateRepository.findByManagedByIdAndDeletedAtIsNull(supervisorId))
                .thenReturn(List.of(managed));
        when(candidateRepository.findByIdInAndDeletedAtIsNull(List.of(managedId)))
                .thenReturn(List.of(managed));

        UserPrincipal supervisor = new UserPrincipal(supervisorId, "supervisor@example.test", List.of("SUPERVISOR"));

        assertEquals(List.of(managedId), scopeService.supervisedCandidateIds(supervisor));
        assertEquals(List.of(managed), scopeService.supervisedCandidates(supervisor));
    }

    @Test
    void adminHasGlobalAccessButOwnScopeStillUsesAssignment() {
        User admin = user(adminId);
        InternshipAssignment assignment = assignmentWithUser(admin);
        when(assignmentRepository.findByInternshipIdAndStatus(internshipId, AssignmentStatus.ACTIVE))
                .thenReturn(java.util.Optional.of(assignment));

        UserPrincipal principal = new UserPrincipal(adminId, admin.getEmail(), List.of("ADMIN"));

        assertTrue(scopeService.hasGlobalAccess(principal));
        assertTrue(scopeService.canManage(principal, UUID.randomUUID()));
        assertTrue(scopeService.isAssignedTo(principal, internshipId));
    }

    @Test
    void supervisorCanManageOnlyTheirAssignedInternship() {
        InternshipAssignment assignment = assignmentWithUser(user(supervisorId));
        when(assignmentRepository.findByInternshipIdAndStatus(internshipId, AssignmentStatus.ACTIVE))
                .thenReturn(java.util.Optional.of(assignment));

        UserPrincipal supervisor = new UserPrincipal(supervisorId, "supervisor@example.test", List.of("SUPERVISOR"));
        UserPrincipal otherSupervisor = new UserPrincipal(otherSupervisorId, "other@example.test", List.of("SUPERVISOR"));

        assertTrue(scopeService.canManage(supervisor, internshipId));
        assertFalse(scopeService.canManage(otherSupervisor, internshipId));
    }

    @Test
    void legacyEmployeeLinkRemainsAValidScopeSource() {
        User supervisor = user(supervisorId);
        Employee employee = new Employee();
        employee.setUser(supervisor);
        InternshipAssignment assignment = new InternshipAssignment();
        assignment.setSupervisor(employee);
        when(assignmentRepository.findByInternshipIdAndStatus(internshipId, AssignmentStatus.ACTIVE))
                .thenReturn(java.util.Optional.of(assignment));

        UserPrincipal principal = new UserPrincipal(supervisorId, supervisor.getEmail(), List.of("SUPERVISOR"));

        assertTrue(scopeService.isAssignedTo(principal, internshipId));
    }

    private InternshipAssignment assignmentWithUser(User user) {
        InternshipAssignment assignment = new InternshipAssignment();
        assignment.setSupervisorUser(user);
        return assignment;
    }

    private User user(UUID id) {
        User user = new User("user-" + id + "@example.test", "hash", UserStatus.ACTIVE);
        user.setId(id);
        return user;
    }
}