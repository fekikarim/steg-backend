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

/**
 * T03 student-defined task category (ST-TASK-03, D5/D5b).
 *
 * <p>A category belongs to exactly one student ({@code ownerUser}): it is a
 * private organisation tool, never exposed to supervisors or admins. A task
 * carries at most one category through {@link Task#getTaskCategory}, and the
 * link is independent of the task workflow status (BR-19).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "task_categories")
public class TaskCategory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User ownerUser;

    @Column(name = "name", nullable = false, length = 40)
    private String name;

    /** Optional colour token from the closed {@code COLOR_TOKENS} list (may be null). */
    @Column(name = "color", length = 16)
    private String color;

    @Column(name = "position", nullable = false)
    private int position;

    public TaskCategory(User ownerUser, String name, String color, int position) {
        this.ownerUser = ownerUser;
        this.name = name;
        this.color = color;
        this.position = position;
    }
}
