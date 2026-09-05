package tn.steg.backend.comment.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.comment.application.dto.CommentRequest;
import tn.steg.backend.comment.application.dto.CommentResponse;
import tn.steg.backend.comment.domain.model.Comment;
import tn.steg.backend.comment.domain.repository.CommentRepository;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.domain.model.Deliverable;
import tn.steg.backend.companion.domain.model.JournalEntry;
import tn.steg.backend.companion.domain.repository.DeliverableRepository;
import tn.steg.backend.companion.domain.repository.JournalEntryRepository;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository commentRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final DeliverableRepository deliverableRepository;
    private final UserRepository userRepository;
    private final InternshipAssignmentRepository assignmentRepository;

    // -------------------------------------------------------------------------
    // Journal Entry Comments
    // -------------------------------------------------------------------------

    @Transactional
    public CommentResponse addJournalEntryComment(UUID entryId, CommentRequest request, UserPrincipal actor) {
        if (request.content() == null || request.content().isBlank()) {
            throw new BusinessRuleException("EMPTY_COMMENT", "Comment content cannot be blank.");
        }

        JournalEntry entry = journalEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Journal entry not found: " + entryId));

        validateParticipant(entry.getJournal().getInternship(), actor);

        User author = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + actor.getId()));

        Comment comment = new Comment(request.content(), author);
        comment.setJournalEntry(entry);
        comment = commentRepository.save(comment);

        log.info("Comment {} added to JournalEntry {} by User {}", comment.getId(), entryId, actor.getId());
        return CommentResponse.from(comment);
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> getJournalEntryComments(UUID entryId, UserPrincipal actor) {
        JournalEntry entry = journalEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Journal entry not found: " + entryId));

        validateParticipant(entry.getJournal().getInternship(), actor);

        return commentRepository.findByJournalEntryIdOrderByCreatedAtAsc(entryId).stream()
                .map(CommentResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Deliverable Comments
    // -------------------------------------------------------------------------

    @Transactional
    public CommentResponse addDeliverableComment(UUID deliverableId, CommentRequest request, UserPrincipal actor) {
        if (request.content() == null || request.content().isBlank()) {
            throw new BusinessRuleException("EMPTY_COMMENT", "Comment content cannot be blank.");
        }

        Deliverable deliverable = deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));

        validateParticipant(deliverable.getInternship(), actor);

        User author = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + actor.getId()));

        Comment comment = new Comment(request.content(), author);
        comment.setDeliverable(deliverable);
        comment = commentRepository.save(comment);

        log.info("Comment {} added to Deliverable {} by User {}", comment.getId(), deliverableId, actor.getId());
        return CommentResponse.from(comment);
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> getDeliverableComments(UUID deliverableId, UserPrincipal actor) {
        Deliverable deliverable = deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));

        validateParticipant(deliverable.getInternship(), actor);

        return commentRepository.findByDeliverableIdOrderByCreatedAtAsc(deliverableId).stream()
                .map(CommentResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Validation Helper
    // -------------------------------------------------------------------------

    private void validateParticipant(Internship internship, UserPrincipal actor) {
        if (actor.hasRole("ADMIN") || actor.hasRole("HR")) {
            return;
        }

        // Intern check
        if (internship.getCandidate() != null
                && internship.getCandidate().getUser() != null
                && internship.getCandidate().getUser().getId().equals(actor.getId())) {
            return;
        }

        // Active Supervisor check
        Optional<InternshipAssignment> activeAssignment = assignmentRepository
                .findByInternshipIdAndStatus(internship.getId(), AssignmentStatus.ACTIVE);

        if (activeAssignment.isPresent()) {
            InternshipAssignment assignment = activeAssignment.get();
            if (assignment.getSupervisor() != null
                    && assignment.getSupervisor().getUser() != null
                    && assignment.getSupervisor().getUser().getId().equals(actor.getId())) {
                return;
            }
        }

        throw new org.springframework.security.access.AccessDeniedException(
                "You are not an active participant (intern or assigned supervisor) of this internship.");
    }
}
