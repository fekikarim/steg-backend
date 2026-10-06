package tn.steg.backend.companion.domain.repository;

import tn.steg.backend.companion.domain.model.TaskCategory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain port for student-owned task categories (T03). */
public interface TaskCategoryRepository {
    Optional<TaskCategory> findById(UUID id);
    List<TaskCategory> findByOwnerUserIdOrderByPositionAscCreatedAtAsc(UUID ownerUserId);
    TaskCategory save(TaskCategory category);
    void delete(TaskCategory category);
}
