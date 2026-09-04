package tn.steg.backend.workflow.domain.model;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.internship.domain.model.Internship;

@Getter
@Setter
@NoArgsConstructor
@Entity
@DiscriminatorValue("INTERNSHIP")
public class InternshipWorkflowInstance extends WorkflowInstance {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "internship_id")
    private Internship internship;

    public InternshipWorkflowInstance(WorkflowDefinition definition, Internship internship) {
        super(definition);
        this.internship = internship;
    }
}
