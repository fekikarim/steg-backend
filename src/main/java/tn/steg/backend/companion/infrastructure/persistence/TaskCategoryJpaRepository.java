package tn.steg.backend.companion.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.companion.domain.model.TaskCategory;
import tn.steg.backend.companion.domain.repository.TaskCategoryRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter for the {@link TaskCategoryRepository} domain port (T03). */
@Repository
public interface TaskCategoryJpaRepository
        extends JpaRepository<TaskCategory, UUID>, TaskCategoryRepository {

    @Override
    List<TaskCategory> findByOwnerUserIdOrderByPositionAscCreatedAtAsc(UUID ownerUserId);

    @Override
    default TaskCategory save(TaskCategory category) {
        return saveAndFlush(category);
    }
}
