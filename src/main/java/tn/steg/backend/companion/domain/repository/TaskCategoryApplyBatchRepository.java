package tn.steg.backend.companion.domain.repository;

import tn.steg.backend.companion.domain.model.TaskCategoryApplyBatch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain port for accepted classification batches (T03 undo support). */
public interface TaskCategoryApplyBatchRepository {
    Optional<TaskCategoryApplyBatch> findById(UUID id);
    List<TaskCategoryApplyBatch> findByUserIdOrderByCreatedAtDesc(UUID userId);
    TaskCategoryApplyBatch save(TaskCategoryApplyBatch batch);
}
