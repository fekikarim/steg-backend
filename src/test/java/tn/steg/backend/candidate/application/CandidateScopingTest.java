package tn.steg.backend.candidate.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tn.steg.backend.candidate.application.dto.CandidateDetailResponse;
import tn.steg.backend.candidate.application.dto.CandidateSummaryResponse;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.candidate.domain.repository.UniversityRepository;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.application.SupervisionScopeService;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests proving:
 * 1. Admin sees ALL candidates (global access)
 * 2. Supervisor sees ONLY own assigned candidates
 * 3. Supervisor gets ResourceNotFoundException for non-assigned candidates (§3.4 — no existence leak)
 * 4. Invalid state transitions return distinct errors (proof for GlobalExceptionHandler 409 mapping)
 */
class CandidateScopingTest {

    private CandidateRepository candidateRepository;
    private UniversityRepository universityRepository;
    private UserRepository userRepository;
    private AuditService auditService;
    private SupervisionScopeService supervisionScopeService;
    private CandidateService candidateService;

    private UUID adminId;
    private UUID supervisorId;
    private UUID otherSupervisorId;
    private Candidate candidateA;
    private Candidate candidateB;
    private Internship internshipA;

    @BeforeEach
    void setUp() {
        candidateRepository = mock(CandidateRepository.class);
        universityRepository = mock(UniversityRepository.class);
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditService.class);
        supervisionScopeService = mock(SupervisionScopeService.class);

        candidateService = new CandidateService(
                candidateRepository, universityRepository, userRepository,
                auditService, supervisionScopeService,
                mock(org.springframework.context.ApplicationEventPublisher.class));

        adminId = UUID.randomUUID();
        supervisorId = UUID.randomUUID();
        otherSupervisorId = UUID.randomUUID();

        University university = new University("TU", "Test University");
        candidateA = new Candidate("Alice", "Admin", "alice@test.com", "hash-a", university);
        candidateA.setId(UUID.randomUUID());
        candidateB = new Candidate("Bob", "Beta", "bob@test.com", "hash-b", university);
        candidateB.setId(UUID.randomUUID());

        internshipA = new Internship("INT-001", candidateA,
                LocalDate.now().minusMonths(3), LocalDate.now().plusMonths(1),
                InternshipType.PERFECTIONNEMENT, InternshipRequirement.OBLIGATOIRE);
        internshipA.setStatus(InternshipStatus.IN_PROGRESS);
    }

    @Test
    void adminSeesAllCandidates() {
        when(candidateRepository.findAll()).thenReturn(List.of(candidateA, candidateB));
        UserPrincipal admin = new UserPrincipal(adminId, "admin@steg.tn", List.of("ADMIN"));

        List<CandidateSummaryResponse> result = candidateService.listCandidates(admin);

        assertThat(result).hasSize(2);
        verify(candidateRepository).findAll();
        verify(supervisionScopeService, never()).assignedInternships(any());
    }

    /**
     * UPDATED in S6a: the list scope no longer re-derives the rule from
     * assigned internships here — it asks SupervisionScopeService, the single
     * owner of "my candidates" (assignment OR managed_by, V44). The rule itself
     * is pinned by SupervisionScopeServiceTest and, against a real database, by
     * StaffCandidateLifecycleIntegrationTest.
     */
    @Test
    void supervisorSeesOnlyOwnCandidates() {
        UserPrincipal supervisor = new UserPrincipal(supervisorId, "sup@steg.tn", List.of("SUPERVISOR"));
        when(supervisionScopeService.supervisedCandidates(supervisor)).thenReturn(List.of(candidateA));

        List<CandidateSummaryResponse> result = candidateService.listCandidates(supervisor);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(candidateA.getId());
        // The candidate module must NOT resolve supervision itself (§3.2).
        verify(supervisionScopeService).supervisedCandidates(supervisor);
        verify(supervisionScopeService, never()).assignedInternships(any());
        verify(candidateRepository, never()).findAll();
    }

    @Test
    void adminSeesAllCandidatesWithoutAskingForAScope() {
        when(candidateRepository.findAll()).thenReturn(List.of(candidateA, candidateB));
        UserPrincipal admin = new UserPrincipal(adminId, "admin@steg.tn", List.of("ADMIN"));

        assertThat(candidateService.listCandidates(admin)).hasSize(2);
        verify(supervisionScopeService, never()).supervisedCandidates(any());
    }

    @Test
    void supervisorGets404ForNonAssignedCandidateDetail() {
        when(candidateRepository.findById(candidateB.getId())).thenReturn(java.util.Optional.of(candidateB));
        // Not in scope: SupervisionScopeService.supervisesCandidate returns false for foreign ids.
        UserPrincipal supervisor = new UserPrincipal(supervisorId, "sup@steg.tn", List.of("SUPERVISOR"));

        // candidateB is NOT in supervisor's assigned internships → must get 404, never leak existence
        assertThatThrownBy(() -> candidateService.getCandidate(candidateB.getId(), supervisor))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(candidateB.getId().toString());
    }

    @Test
    void supervisorCanViewOwnAssignedCandidateDetail() {
        when(candidateRepository.findById(candidateA.getId())).thenReturn(java.util.Optional.of(candidateA));
        when(supervisionScopeService.supervisesCandidate(any(), any())).thenReturn(true);
        UserPrincipal supervisor = new UserPrincipal(supervisorId, "sup@steg.tn", List.of("SUPERVISOR"));

        CandidateDetailResponse response = candidateService.getCandidate(candidateA.getId(), supervisor);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(candidateA.getId());
        // nationalId must NOT be returned to supervisor
        assertThat(response.nationalId()).isNull();
    }

    @Test
    void adminSeesAllCandidatesGloballyAndNotScopedToAssignments() {
        when(candidateRepository.findById(candidateB.getId())).thenReturn(java.util.Optional.of(candidateB));
        UserPrincipal admin = new UserPrincipal(adminId, "admin@steg.tn", List.of("ADMIN"));

        // Admin can view candidateB even if not assigned to any internship
        CandidateDetailResponse response = candidateService.getCandidate(candidateB.getId(), admin);
        assertThat(response).isNotNull();
        verify(supervisionScopeService, never()).assignedInternships(any());
    }
}
