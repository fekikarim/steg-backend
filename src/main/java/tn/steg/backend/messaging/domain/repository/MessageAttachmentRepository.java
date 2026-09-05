package tn.steg.backend.messaging.domain.repository;

import tn.steg.backend.messaging.domain.model.MessageAttachment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port — MessageAttachment persistence.
 */
public interface MessageAttachmentRepository {
    Optional<MessageAttachment> findById(UUID id);
    List<MessageAttachment> findByMessageId(UUID messageId);
    MessageAttachment save(MessageAttachment attachment);
}
