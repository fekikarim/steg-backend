package tn.steg.backend.companion.domain.repository;

import tn.steg.backend.companion.domain.model.TaskDraft;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for S10a AI task drafts (server-side proposals, not real tasks).
 */
public interface TaskDraftRepository {
    TaskDraft save(TaskDraft draft);
    Optional<TaskDraft> findById(UUID id);
    List<TaskDraft> findByCreatedByIdOrderByCreatedAtDesc(UUID createdById);
    void delete(TaskDraft draft);
}
