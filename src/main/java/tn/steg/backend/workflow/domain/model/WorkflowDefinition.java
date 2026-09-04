package tn.steg.backend.workflow.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "workflow_definitions")
public class WorkflowDefinition extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "version_num", nullable = false)
    private Integer versionNum = 1;

    public WorkflowDefinition(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }
}
