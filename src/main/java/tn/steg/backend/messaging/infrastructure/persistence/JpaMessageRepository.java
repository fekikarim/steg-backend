package tn.steg.backend.messaging.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.messaging.domain.model.Message;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JpaMessageRepository
        extends JpaRepository<Message, UUID>,
        tn.steg.backend.messaging.domain.repository.MessageRepository {

    @Query("select max(m.sequenceNumber) from Message m where m.conversation.id = :conversationId")
    Optional<Long> findMaxSequenceNumber(@Param("conversationId") UUID conversationId);

    @Query("select m from Message m where m.conversation.id = :conversationId")
    Page<Message> findByConversationId(@Param("conversationId") UUID conversationId, Pageable pageable);

    Page<Message> findByConversationIdAndSequenceNumberLessThanEqual(
            UUID conversationId, Long maxSequenceNumber, Pageable pageable);

    List<Message> findByConversationIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            UUID conversationId, Long afterSequence);

    long countByConversationId(UUID conversationId);

    long countByConversationIdAndSequenceNumberGreaterThan(UUID conversationId, Long sequenceNumber);
}
