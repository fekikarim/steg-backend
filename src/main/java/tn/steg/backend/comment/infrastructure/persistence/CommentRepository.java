package tn.steg.backend.comment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.comment.domain.model.Comment;

import java.util.List;
import java.util.UUID;

@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID>, tn.steg.backend.comment.domain.repository.CommentRepository {
    List<Comment> findByJournalEntryIdOrderByCreatedAtAsc(UUID journalEntryId);
    List<Comment> findByDeliverableIdOrderByCreatedAtAsc(UUID deliverableId);
    List<Comment> findByEvaluationIdOrderByCreatedAtAsc(UUID evaluationId);
}
