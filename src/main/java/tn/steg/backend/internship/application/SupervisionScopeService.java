package tn.steg.backend.internship.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONE definition of "my candidates" (AGENTS.md §3.2: "Centralize this in one
 * backend component... No module may re-implement its own scoping logic").
 *
 * <p>A candidate is inside a staff member's scope when EITHER
 * <ul>
 *   <li>they are an ADMIN (global access, and the Admin is also a supervisor), or</li>
 *   <li>an ACTIVE internship assignment points at them, or the internship row
 *       names them as supervisor, or</li>
 *   <li>{@code candidates.managed_by_user_id} points at them (V44 — the profile
 *       was created by staff, AGENTS.md A4, and has no internship yet).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SupervisionScopeService {

    private final InternshipAssignmentRepository assignmentRepository;
    private final InternshipRepository internshipRepository;
    private final CandidateRepository candidateRepository;

    public boolean hasGlobalAccess(UserPrincipal actor) {
        return actor != null && actor.hasRole("ADMIN");
    }

    @Transactional(readOnly = true)
    public boolean isAssignedTo(UserPrincipal actor, UUID internshipId) {
        return actor != null && internshipId != null && isAssignedTo(actor.getId(), internshipId);
    }

    @Transactional(readOnly = true)
    public boolean isAssignedTo(UUID userId, UUID internshipId) {
        if (userId == null || internshipId == null) {
            return false;
        }
        Optional<UUID> supFromAssignment = assignmentRepository.findByInternshipIdAndStatus(internshipId, AssignmentStatus.ACTIVE)
                .map(assignment -> assignment.getSupervisorUser() != null
                        ? assignment.getSupervisorUser().getId()
                        : assignment.getSupervisor() != null && assignment.getSupervisor().getUser() != null
                                ? assignment.getSupervisor().getUser().getId()
                                : null);

        if (supFromAssignment.isPresent()) {
            return userId.equals(supFromAssignment.get());
        }

        return internshipRepository.findById(internshipId)
                .map(i -> i.getSupervisorUser() != null && userId.equals(i.getSupervisorUser().getId()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean canManage(UserPrincipal actor, UUID internshipId) {
        return hasGlobalAccess(actor) || isAssignedTo(actor, internshipId);
    }

    /**
     * True when {@code candidateId} is inside the actor's supervision scope:
     * Admin globally (§3.2), a Supervisor through his assigned internships
     * (user-backed assignment row or direct internship link) OR through the
     * explicit {@code managed_by_user_id} link written when staff created the
     * profile (§5.1 / A4). Single-resource accessors use this to answer
     * "out of scope → 404, never 403" (§3.4).
     */
    @Transactional(readOnly = true)
    public boolean supervisesCandidate(UserPrincipal actor, UUID candidateId) {
        if (actor == null || candidateId == null) {
            return false;
        }
        if (hasGlobalAccess(actor)) {
            return true;
        }
        if (candidateRepository.existsByIdAndManagedByIdAndDeletedAtIsNull(candidateId, actor.getId())) {
            return true;
        }
        return assignedInternships(actor).stream()
                .anyMatch(i -> i.getCandidate() != null && candidateId.equals(i.getCandidate().getId()));
    }

    /**
     * The candidate ids inside the actor's scope — the list form of
     * {@link #supervisesCandidate}, used by every scoped list query so a
     * workspace never re-derives the rule. Admin: empty list means "no
     * restriction" (callers pass {@code scoped=false}); a Supervisor: the union
     * of his assigned internships' candidates and the profiles he manages.
     */
    @Transactional(readOnly = true)
    public List<UUID> supervisedCandidateIds(UserPrincipal actor) {
        if (actor == null || hasGlobalAccess(actor)) {
            return List.of();
        }
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (Internship internship : assignedInternships(actor)) {
            if (internship.getCandidate() != null) {
                ids.add(internship.getCandidate().getId());
            }
        }
        candidateRepository.findByManagedByIdAndDeletedAtIsNull(actor.getId()).stream()
                .map(Candidate::getId)
                .forEach(ids::add);
        return new ArrayList<>(ids);
    }

    /**
     * The candidates inside the actor's scope (entities). Same rule as
     * {@link #supervisedCandidateIds}; used where the caller needs the rows.
     */
    @Transactional(readOnly = true)
    public List<Candidate> supervisedCandidates(UserPrincipal actor) {
        if (actor == null || hasGlobalAccess(actor)) {
            return List.of();
        }
        List<UUID> ids = supervisedCandidateIds(actor);
        return ids.isEmpty() ? List.of() : candidateRepository.findByIdInAndDeletedAtIsNull(ids);
    }

    @Transactional(readOnly = true)
    public List<Internship> assignedInternships(UserPrincipal actor) {
        if (actor == null) {
            return List.of();
        }
        List<Internship> fromAssignments = assignmentRepository.findBySupervisorUserIdAndStatus(actor.getId(), AssignmentStatus.ACTIVE)
                .stream()
                .map(InternshipAssignment::getInternship)
                .toList();

        List<Internship> fromDirect = internshipRepository.findBySupervisorUserId(actor.getId());

        LinkedHashSet<Internship> combined = new LinkedHashSet<>(fromAssignments);
        combined.addAll(fromDirect);
        return new ArrayList<>(combined);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findSupervisorUserId(UUID internshipId) {
        if (internshipId == null) {
            return Optional.empty();
        }
        Optional<UUID> fromAssignment = assignmentRepository.findByInternshipIdAndStatus(internshipId, AssignmentStatus.ACTIVE)
                .map(assignment -> assignment.getSupervisorUser() != null
                        ? assignment.getSupervisorUser().getId()
                        : assignment.getSupervisor() != null && assignment.getSupervisor().getUser() != null
                                ? assignment.getSupervisor().getUser().getId()
                                : null);
        if (fromAssignment.isPresent()) {
            return fromAssignment;
        }
        return internshipRepository.findById(internshipId)
                .map(i -> i.getSupervisorUser() != null ? i.getSupervisorUser().getId() : null);
    }
}