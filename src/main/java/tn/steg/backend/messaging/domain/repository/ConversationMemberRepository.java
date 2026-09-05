package tn.steg.backend.messaging.domain.repository;

import tn.steg.backend.messaging.domain.model.ConversationMember;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port — ConversationMember persistence.
 * Soft-leave is modeled via {@code leftAt}; rows are never deleted.
 */
public interface ConversationMemberRepository {
    Optional<ConversationMember> findById(UUID id);
    Optional<ConversationMember> findByConversationIdAndUserId(UUID conversationId, UUID userId);
    Optional<ConversationMember> findActiveByConversationIdAndUserId(UUID conversationId, UUID userId);
    List<ConversationMember> findActiveByConversationId(UUID conversationId);
    List<ConversationMember> findActiveByUserId(UUID userId);
    long countActiveByConversationId(UUID conversationId);
    ConversationMember save(ConversationMember member);
}
