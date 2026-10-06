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

import java.util.ArrayList;
import java.util.List;

/**
 * T03 accepted classification batch (ST-TASK-04): every accepted AI/manual
 * batch is persisted with the previous category per task, so an undo restores
 * exactly those tasks — and only the ones the student has not changed since
 * (compare-and-set on {@link TaskCategoryApplyItem}).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "task_category_apply_batches")
public class TaskCategoryApplyBatch extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "internship_id", nullable = false)
    private Internship internship;

    @jakarta.persistence.OneToMany(
            mappedBy = "batch",
            cascade = jakarta.persistence.CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<TaskCategoryApplyItem> items = new ArrayList<>();

    public TaskCategoryApplyBatch(User user, Internship internship) {
        this.user = user;
        this.internship = internship;
    }

    public void addItem(TaskCategoryApplyItem item) {
        item.setBatch(this);
        this.items.add(item);
    }
}
