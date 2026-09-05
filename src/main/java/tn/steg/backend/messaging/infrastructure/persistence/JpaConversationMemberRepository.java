package tn.steg.backend.messaging.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.messaging.domain.model.ConversationMember;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JpaConversationMemberRepository
        extends JpaRepository<ConversationMember, UUID>,
        tn.steg.backend.messaging.domain.repository.ConversationMemberRepository {

    Optional<ConversationMember> findByConversationIdAndUserId(UUID conversationId, UUID userId);

    @Query("select m from ConversationMember m where m.conversation.id = :conversationId and m.user.id = :userId and m.leftAt is null")
    Optional<ConversationMember> findActiveByConversationIdAndUserId(
            @Param("conversationId") UUID conversationId, @Param("userId") UUID userId);

    @Query("select m from ConversationMember m where m.conversation.id = :conversationId and m.leftAt is null")
    List<ConversationMember> findActiveByConversationId(@Param("conversationId") UUID conversationId);

    @Query("select m from ConversationMember m where m.user.id = :userId and m.leftAt is null")
    List<ConversationMember> findActiveByUserId(@Param("userId") UUID userId);

    @Query("select count(m) from ConversationMember m where m.conversation.id = :conversationId and m.leftAt is null")
    long countActiveByConversationId(@Param("conversationId") UUID conversationId);
}
