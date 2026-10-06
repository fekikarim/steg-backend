package tn.steg.backend.companion.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.companion.domain.model.TaskCategoryApplyBatch;
import tn.steg.backend.companion.domain.repository.TaskCategoryApplyBatchRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter for the {@link TaskCategoryApplyBatchRepository} domain port (T03). */
@Repository
public interface TaskCategoryApplyBatchJpaRepository
        extends JpaRepository<TaskCategoryApplyBatch, UUID>, TaskCategoryApplyBatchRepository {

    @Override
    List<TaskCategoryApplyBatch> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
