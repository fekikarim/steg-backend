package tn.steg.backend.workflow.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "workflow_step_definitions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"definition_id", "code"})
)
public class WorkflowStepDefinition extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_id", nullable = false)
    private WorkflowDefinition definition;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "sequence_num", nullable = false)
    private Integer sequenceNum;

    @Column(name = "required", nullable = false)
    private Boolean required = true;

    public WorkflowStepDefinition(WorkflowDefinition definition, String code, String name, Integer sequenceNum) {
        this.definition = definition;
        this.code = code;
        this.name = name;
        this.sequenceNum = sequenceNum;
    }
}
