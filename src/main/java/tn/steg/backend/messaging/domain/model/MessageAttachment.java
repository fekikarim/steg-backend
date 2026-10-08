package tn.steg.backend.messaging.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;
import tn.steg.backend.companion.domain.model.Deliverable;
import tn.steg.backend.document.domain.model.FileAsset;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "message_attachments")
public class MessageAttachment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_asset_id", nullable = false)
    private FileAsset file;

    /**
     * T10/SU-VAL-01: when the sender attached one of the internship's
     * documents (the journal/report picker), the source deliverable is kept so
     * the supervisor's "set as journal / set as report" action targets exactly
     * the document received. Null for ordinary chat files.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_deliverable_id")
    private Deliverable sourceDeliverable;

    @Column(name = "attached_at", nullable = false)
    private Instant attachedAt;

    public MessageAttachment(Message message, FileAsset file) {
        this(message, file, null);
    }

    public MessageAttachment(Message message, FileAsset file, Deliverable sourceDeliverable) {
        this.message = message;
        this.file = file;
        this.sourceDeliverable = sourceDeliverable;
        this.attachedAt = Instant.now();
    }
}
