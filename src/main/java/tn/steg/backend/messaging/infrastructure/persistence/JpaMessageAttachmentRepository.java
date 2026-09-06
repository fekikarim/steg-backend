package tn.steg.backend.messaging.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.messaging.domain.model.MessageAttachment;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface JpaMessageAttachmentRepository
        extends JpaRepository<MessageAttachment, UUID>,
        tn.steg.backend.messaging.domain.repository.MessageAttachmentRepository {

    List<MessageAttachment> findByMessageId(UUID messageId);

    /**
     * A14 N+1 fix backing the domain port method: attachments for many messages
     * with file metadata fetched eagerly (rendered per attachment).
     */
    @Query("SELECT DISTINCT a FROM MessageAttachment a " +
           "LEFT JOIN FETCH a.file WHERE a.message.id IN :messageIds")
    List<MessageAttachment> findByMessageIdInWithFile(@Param("messageIds") Collection<UUID> messageIds);
}
