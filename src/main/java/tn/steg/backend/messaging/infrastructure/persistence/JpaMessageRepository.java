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

    /**
     * A14 N+1 fix backing the domain port methods: sender fetched eagerly for
     * history pages (single-valued join is pagination-safe; separate count).
     */
    @Query(value = "SELECT m FROM Message m LEFT JOIN FETCH m.sender WHERE m.conversation.id = :conversationId",
           countQuery = "SELECT COUNT(m) FROM Message m WHERE m.conversation.id = :conversationId")
    Page<Message> findByConversationIdWithSender(@Param("conversationId") UUID conversationId, Pageable pageable);

    @Query(value = "SELECT m FROM Message m LEFT JOIN FETCH m.sender WHERE m.conversation.id = :conversationId "
                 + "AND m.sequenceNumber <= :maxSequenceNumber",
           countQuery = "SELECT COUNT(m) FROM Message m WHERE m.conversation.id = :conversationId "
                      + "AND m.sequenceNumber <= :maxSequenceNumber")
    Page<Message> findByConversationIdAndSequenceNumberLessThanEqualWithSender(
            @Param("conversationId") UUID conversationId,
            @Param("maxSequenceNumber") Long maxSequenceNumber, Pageable pageable);

    Page<Message> findByConversationIdAndSequenceNumberLessThanEqual(
            UUID conversationId, Long maxSequenceNumber, Pageable pageable);

    List<Message> findByConversationIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            UUID conversationId, Long afterSequence);

    long countByConversationId(UUID conversationId);

    long countByConversationIdAndSequenceNumberGreaterThan(UUID conversationId, Long sequenceNumber);

    List<Message> findByConversationIdAndSequenceNumberLessThanEqualOrderBySequenceNumberAsc(
            UUID conversationId, Long maxSequenceNumber);

    @Query("select count(m) from Message m where m.conversation.id = :conversationId "
            + "and m.sequenceNumber > :afterSequenceNumber and m.sender.id <> :excludeSenderId")
    long countUnread(@Param("conversationId") UUID conversationId,
                     @Param("afterSequenceNumber") Long afterSequenceNumber,
                     @Param("excludeSenderId") UUID excludeSenderId);
}
