package tn.steg.backend.document.domain.model;

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
import tn.steg.backend.internship.domain.model.Internship;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "internship_documents")
public class InternshipDocument extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "internship_id", nullable = false)
    private Internship internship;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "mandatory", nullable = false)
    private Boolean mandatory = true;

    @Column(name = "generated_automatically", nullable = false)
    private Boolean generatedAutomatically = false;

    public InternshipDocument(Internship internship, Document document, Boolean mandatory) {
        this.internship = internship;
        this.document = document;
        this.mandatory = mandatory;
    }
}
