package tn.steg.backend.companion.domain.model;

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
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.internship.domain.model.Internship;

import java.time.LocalDate;

/**
 * S10a AI task draft (AGENTS.md §7.4): a server-side proposal that becomes a
 * real {@link Task} only when bulk-added to one or more students.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_task_drafts")
public class TaskDraft extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    /**
     * Internship anchoring the period used to validate AI-proposed due dates
     * at generation time (and the scope check for supervisors).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reference_internship_id", nullable = false)
    private Internship referenceInternship;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "due_date")
    private LocalDate dueDate;

    public TaskDraft(User createdBy, Internship referenceInternship,
                     String title, String description, LocalDate dueDate) {
        this.createdBy = createdBy;
        this.referenceInternship = referenceInternship;
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
    }
}
