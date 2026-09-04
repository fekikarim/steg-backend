package tn.steg.backend.companion.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
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
@Table(name = "internship_journals")
public class InternshipJournal extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "internship_id", nullable = false, unique = true)
    private Internship internship;

    public InternshipJournal(Internship internship) {
        this.internship = internship;
    }
}
