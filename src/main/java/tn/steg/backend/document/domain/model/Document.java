package tn.steg.backend.document.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "documents")
public class Document extends BaseEntity {

    @Column(name = "reference", nullable = false, unique = true, length = 50)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private DocumentType type;

    @Column(name = "restricted_access", nullable = false)
    private Boolean restrictedAccess = false;

    @Column(name = "generated_automatically", nullable = false)
    private Boolean generatedAutomatically = false;

    public Document(String reference, DocumentType type) {
        this.reference = reference;
        this.type = type;
        // Default restricted access to true for sensitive document types
        if (type == DocumentType.CIN_COPY) {
            this.restrictedAccess = true;
        }
    }
}
