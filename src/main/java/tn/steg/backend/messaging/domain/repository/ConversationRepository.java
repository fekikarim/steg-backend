package tn.steg.backend.messaging.domain.repository;

import tn.steg.backend.messaging.domain.model.Conversation;
import tn.steg.backend.messaging.domain.model.ConversationType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port — Conversation persistence.
 * Infrastructure adapter extends this alongside Spring Data JPA.
 */
public interface ConversationRepository {
    Optional<Conversation> findById(UUID id);
    Optional<Conversation> findByIdForUpdate(UUID id);
    List<Conversation> findByInternshipId(UUID internshipId);
    Optional<Conversation> findPrivateByInternshipId(UUID internshipId);
    List<Conversation> findByType(ConversationType type);
    Conversation save(Conversation conversation);
}
