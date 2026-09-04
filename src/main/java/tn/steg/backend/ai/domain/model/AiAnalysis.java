package tn.steg.backend.ai.domain.model;

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

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_analyses")
public class AiAnalysis extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private AiAnalysisType type;

    @Column(name = "related_entity_type", nullable = false, length = 100)
    private String relatedEntityType;

    @Column(name = "related_entity_id", nullable = false)
    private UUID relatedEntityId;

    @Column(name = "model_used", length = 100)
    private String modelUsed;

    @Column(name = "input_summary", columnDefinition = "TEXT")
    private String inputSummary;

    @Column(name = "output_summary", columnDefinition = "TEXT")
    private String outputSummary;

    @Column(name = "cin_excluded", nullable = false)
    private Boolean cinExcluded = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_id")
    private User requestedBy;

    public AiAnalysis(AiAnalysisType type, String relatedEntityType, UUID relatedEntityId, String modelUsed) {
        this.type = type;
        this.relatedEntityType = relatedEntityType;
        this.relatedEntityId = relatedEntityId;
        this.modelUsed = modelUsed;
        this.cinExcluded = true;
    }
}
