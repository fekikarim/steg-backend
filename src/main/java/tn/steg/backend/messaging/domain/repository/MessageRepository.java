package tn.steg.backend.messaging.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.steg.backend.messaging.domain.model.Message;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port — Message persistence.
 * Ordering is authoritative via {@code sequenceNumber} (monotonic per conversation),
 * never via {@code sentAt} alone.
 */
public interface MessageRepository {
    Optional<Message> findById(UUID id);
    Message save(Message message);
    void deleteById(UUID id);
    Optional<Long> findMaxSequenceNumber(UUID conversationId);
    Page<Message> findByConversationId(UUID conversationId, Pageable pageable);
    Page<Message> findByConversationIdAndSequenceNumberLessThanEqual(
            UUID conversationId, Long maxSequenceNumber, Pageable pageable);
    List<Message> findByConversationIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            UUID conversationId, Long afterSequence);
    long countByConversationId(UUID conversationId);
    long countByConversationIdAndSequenceNumberGreaterThan(UUID conversationId, Long sequenceNumber);
}
