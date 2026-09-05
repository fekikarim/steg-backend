package tn.steg.backend.comment.domain.repository;

import tn.steg.backend.comment.domain.model.Comment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommentRepository {
    Optional<Comment> findById(UUID id);
    Comment save(Comment comment);
    List<Comment> findByJournalEntryIdOrderByCreatedAtAsc(UUID journalEntryId);
    List<Comment> findByDeliverableIdOrderByCreatedAtAsc(UUID deliverableId);
    List<Comment> findByEvaluationIdOrderByCreatedAtAsc(UUID evaluationId);
}
