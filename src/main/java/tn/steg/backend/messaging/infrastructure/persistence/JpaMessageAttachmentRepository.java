package tn.steg.backend.messaging.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.messaging.domain.model.MessageAttachment;

import java.util.List;
import java.util.UUID;

@Repository
public interface JpaMessageAttachmentRepository
        extends JpaRepository<MessageAttachment, UUID>,
        tn.steg.backend.messaging.domain.repository.MessageAttachmentRepository {

    List<MessageAttachment> findByMessageId(UUID messageId);
}
