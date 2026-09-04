package tn.steg.backend.workflow.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;
import tn.steg.backend.iam.domain.model.User;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "workflow_actions")
public class WorkflowAction extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instance_id", nullable = false)
    private WorkflowInstance instance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_id", nullable = false)
    private WorkflowStepDefinition step;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performed_by_id", nullable = false)
    private User performedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private WorkflowActionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 50)
    private ApprovalDecision decision;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "sequence_number", nullable = false)
    private Long sequenceNumber;

    @Column(name = "performed_at", nullable = false)
    private Instant performedAt;

    public WorkflowAction(WorkflowInstance instance, WorkflowStepDefinition step, User performedBy,
                          WorkflowActionType type, ApprovalDecision decision, String comment, Long sequenceNumber) {
        this.instance = instance;
        this.step = step;
        this.performedBy = performedBy;
        this.type = type;
        this.decision = decision;
        this.comment = comment;
        this.sequenceNumber = sequenceNumber;
        this.performedAt = Instant.now();
    }
}
