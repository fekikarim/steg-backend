package tn.steg.backend.workflow.domain.model;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.application.domain.model.InternshipApplication;

@Getter
@Setter
@NoArgsConstructor
@Entity
@DiscriminatorValue("APPLICATION")
public class ApplicationWorkflowInstance extends WorkflowInstance {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id")
    private InternshipApplication application;

    public ApplicationWorkflowInstance(WorkflowDefinition definition, InternshipApplication application) {
        super(definition);
        this.application = application;
    }
}
