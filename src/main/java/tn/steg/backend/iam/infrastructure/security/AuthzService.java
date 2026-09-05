package tn.steg.backend.iam.infrastructure.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.domain.model.Deliverable;
import tn.steg.backend.companion.domain.model.JournalEntry;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.repository.DeliverableRepository;
import tn.steg.backend.companion.domain.repository.JournalEntryRepository;
import tn.steg.backend.companion.domain.repository.TaskRepository;
import tn.steg.backend.evaluation.domain.model.Evaluation;
import tn.steg.backend.evaluation.domain.repository.EvaluationDomainRepository;
import tn.steg.backend.messaging.domain.repository.ConversationMemberRepository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Authorization evaluator bean exposed as {@code @authz} for SpEL expressions in {@code @PreAuthorize}.
 *
 * Example usages:
 * <pre>
 *   {@code @PreAuthorize("@authz.hasRole('FINANCE')")}
 *   {@code @PreAuthorize("@authz.isCurrentUser(#userId)")}
 *   {@code @PreAuthorize("@authz.isSupervisorOf(#internshipId)")}
 *   {@code @PreAuthorize("@authz.isInternOf(#internshipId)")}
 * </pre>
 *
 * <p>Check methods run in short read-only transactions: {@code @PreAuthorize}
 * evaluates before any service transaction opens, so lazy association
 * traversal here needs its own session (otherwise LazyInitializationException
 * outside tests, which always run inside one).
 */
@Slf4j
@Component("authz")
@RequiredArgsConstructor
public class AuthzService {

    private final InternshipAssignmentRepository assignmentRepository;
    private final InternshipRepository internshipRepository;
    private final TaskRepository taskRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final DeliverableRepository deliverableRepository;
    private final EvaluationDomainRepository evaluationRepository;
    private final ConversationMemberRepository conversationMemberRepository;

    public boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
    }

    public boolean isCurrentUser(UUID userId) {
        if (userId == null) {
            return false;
        }
        UUID currentId = getCurrentUserId();
        return userId.equals(currentId);
    }

    public boolean hasRole(String role) {
        if (role == null) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        String target = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equalsIgnoreCase(target));
    }

    public boolean hasAnyRole(String... roles) {
        if (roles == null || roles.length == 0) {
            return false;
        }
        for (String role : roles) {
            if (hasRole(role)) {
                return true;
            }
        }
        return false;
    }

    public UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return principal.getId();
        }
        return null;
    }

    public String getCurrentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            if (auth.getPrincipal() instanceof UserPrincipal principal) {
                return principal.getEmail();
            } else if (auth.getPrincipal() instanceof String email) {
                return email;
            }
        }
        return null;
    }

    /**
     * Phase A9: verifies the caller is an <em>active</em> ({@code leftAt IS NULL})
     * member of the conversation. Backs every messaging endpoint via
     * {@code @PreAuthorize("@authz.isOwnConversation(#conversationId)")}.
     * Unknown ids return false (→ 403/404) without leaking existence.
     * Row-based on purpose: the revoke-mode GROUP standing policy is enforced
     * inside {@code MessagingService} (so relinquishing access via leave keeps
     * working); every other messaging operation re-checks there.
     */
    @Transactional(readOnly = true)
    public boolean isOwnConversation(UUID conversationId) {
        if (conversationId == null || !isAuthenticated()) {
            return false;
        }
        if (hasRole("ADMIN")) {
            return true;
        }
        UUID currentId = getCurrentUserId();
        if (currentId == null) {
            return false;
        }
        try {
            return conversationMemberRepository
                    .findActiveByConversationIdAndUserId(conversationId, currentId)
                    .isPresent();
        } catch (Exception e) {
            log.debug("Conversation membership check failed for conv={}: {}", conversationId, e.getMessage());
            return false;
        }
    }

    public boolean isConversationMember(UUID conversationId) {
        return isOwnConversation(conversationId);
    }

    // -------------------------------------------------------------------------
    // Companion authorization methods
    // -------------------------------------------------------------------------

    private UserPrincipal getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) {
            return null;
        }
        return (UserPrincipal) auth.getPrincipal();
    }

    @Transactional(readOnly = true)
    public boolean isSupervisorOf(UUID id) {
        UserPrincipal actor = getCurrentUser();
        if (actor == null) return false;
        if (actor.hasRole("ADMIN") || actor.hasRole("HR")) return true;

        UUID internshipId = resolveInternshipId(id);
        if (internshipId == null) return false;

        Optional<InternshipAssignment> activeAssignment = assignmentRepository
                .findByInternshipIdAndStatus(internshipId, AssignmentStatus.ACTIVE);

        if (activeAssignment.isEmpty()) return false;

        InternshipAssignment assignment = activeAssignment.get();
        if (assignment.getSupervisor() == null || assignment.getSupervisor().getUser() == null) {
            return false;
        }

        return assignment.getSupervisor().getUser().getId().equals(actor.getId());
    }

    @Transactional(readOnly = true)
    public boolean isInternOf(UUID id) {
        UserPrincipal actor = getCurrentUser();
        if (actor == null) return false;
        if (actor.hasRole("ADMIN") || actor.hasRole("HR")) return true;

        UUID internshipId = resolveInternshipId(id);
        if (internshipId == null) return false;

        Optional<Internship> internship = internshipRepository.findById(internshipId);
        if (internship.isEmpty()) return false;

        Internship i = internship.get();
        if (i.getCandidate() == null || i.getCandidate().getUser() == null) {
            return false;
        }

        return i.getCandidate().getUser().getId().equals(actor.getId());
    }

    @Transactional(readOnly = true)
    public boolean isParticipantOf(UUID id) {
        return isSupervisorOf(id) || isInternOf(id);
    }

    private UUID resolveInternshipId(UUID targetId) {
        // Check if direct internship ID
        Optional<Internship> directInternship = internshipRepository.findById(targetId);
        if (directInternship.isPresent()) {
            return targetId;
        }

        // Check if Task ID
        Optional<Task> task = taskRepository.findById(targetId);
        if (task.isPresent() && task.get().getInternship() != null) {
            return task.get().getInternship().getId();
        }

        // Check if Deliverable ID
        Optional<Deliverable> deliverable = deliverableRepository.findById(targetId);
        if (deliverable.isPresent() && deliverable.get().getInternship() != null) {
            return deliverable.get().getInternship().getId();
        }

        // Check if JournalEntry ID
        Optional<JournalEntry> journalEntry = journalEntryRepository.findById(targetId);
        if (journalEntry.isPresent() && journalEntry.get().getJournal() != null
                && journalEntry.get().getJournal().getInternship() != null) {
            return journalEntry.get().getJournal().getInternship().getId();
        }

        // Check if Evaluation ID
        Optional<Evaluation> evaluation = evaluationRepository.findById(targetId);
        if (evaluation.isPresent() && evaluation.get().getInternship() != null) {
            return evaluation.get().getInternship().getId();
        }

        return null;
    }
}
