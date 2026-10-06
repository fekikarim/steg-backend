package tn.steg.backend.companion.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;

/**
 * T03 one line of an accepted classification batch: the task, the category
 * that was applied, and the category that was there before (null when the
 * task was unclassified). Undo reverts a line only when the task still
 * carries the applied category.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "task_category_apply_items")
public class TaskCategoryApplyItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private TaskCategoryApplyBatch batch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_category_id")
    private TaskCategory previousCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applied_category_id")
    private TaskCategory appliedCategory;

    public TaskCategoryApplyItem(Task task, TaskCategory previousCategory, TaskCategory appliedCategory) {
        this.task = task;
        this.previousCategory = previousCategory;
        this.appliedCategory = appliedCategory;
    }
}
