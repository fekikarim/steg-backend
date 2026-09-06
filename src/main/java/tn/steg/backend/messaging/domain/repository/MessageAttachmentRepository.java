package tn.steg.backend.messaging.domain.repository;

import tn.steg.backend.messaging.domain.model.MessageAttachment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port — MessageAttachment persistence.
 */
public interface MessageAttachmentRepository {
    Optional<MessageAttachment> findById(UUID id);
    List<MessageAttachment> findByMessageId(UUID messageId);
    List<MessageAttachment> findByMessageIdIn(Collection<UUID> messageIds);
    /**
     * A14 N+1 fix: bulk-load attachments for a whole history page with their
     * file metadata fetched eagerly (rendered per attachment). Backed by the
     * fetch-join query on the infrastructure adapter.
     */
    List<MessageAttachment> findByMessageIdInWithFile(Collection<UUID> messageIds);
    MessageAttachment save(MessageAttachment attachment);
}
